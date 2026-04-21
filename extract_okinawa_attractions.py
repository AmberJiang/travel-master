import argparse
import json
import os
from pathlib import Path
from typing import Any, Dict, List

import pytesseract
import requests
from pydantic import ValidationError
from PIL import ImageEnhance, ImageOps
from pypdf import PdfReader
from pypdfium2 import PdfDocument

from data.schema import OkinawaAttraction


DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions"


def load_env_var(key: str, env_path: Path) -> str:
    if key in os.environ:
        return os.environ[key]
    if not env_path.exists():
        raise FileNotFoundError(f"未找到环境变量文件: {env_path}")
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        if k.strip() == key:
            return v.strip().strip('"').strip("'")
    raise ValueError(f"在 {env_path} 中未找到 {key}")


def extract_pdf_text(pdf_path: Path) -> str:
    reader = PdfReader(str(pdf_path))
    parts = []
    for page in reader.pages:
        parts.append(page.extract_text() or "")
    return "\n".join(parts).strip()


def extract_pdf_text_with_ocr(pdf_path: Path, dpi: int = 300) -> str:
    doc = PdfDocument(str(pdf_path))
    scale = max(1, round(dpi / 72))
    parts = []
    def _threshold(pixel: int) -> int:
        return 255 if pixel > 160 else 0

    for page in doc:
        bitmap = page.render(scale=scale).to_pil()
        bitmap = ImageOps.grayscale(bitmap)
        bitmap = ImageEnhance.Contrast(bitmap).enhance(1.8)
        bitmap = bitmap.point(_threshold)
        text = pytesseract.image_to_string(
            bitmap,
            lang="chi_sim+chi_tra+jpn+eng",
            config="--oem 1 --psm 11",
        ).strip()
        if text:
            parts.append(text)
    return "\n".join(parts).strip()


def extract_text_with_fallback(pdf_path: Path) -> str:
    text = extract_pdf_text(pdf_path)
    if text:
        return text
    try:
        return extract_pdf_text_with_ocr(pdf_path)
    except pytesseract.TesseractNotFoundError as err:
        raise RuntimeError(
            "未检测到 tesseract 可执行程序，请先安装后再运行。"
            "macOS 可用: brew install tesseract tesseract-lang"
        ) from err


def split_text_chunks(text: str, max_chars: int = 6000) -> List[str]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    chunks: List[str] = []
    current: List[str] = []
    current_len = 0
    for line in lines:
        if current_len + len(line) + 1 > max_chars and current:
            chunks.append("\n".join(current))
            current = [line]
            current_len = len(line)
        else:
            current.append(line)
            current_len += len(line) + 1
    if current:
        chunks.append("\n".join(current))
    return chunks


def clean_text_for_llm(text: str) -> str:
    cleaned_lines: List[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        cjk_count = sum(1 for ch in line if "\u4e00" <= ch <= "\u9fff")
        digit_count = sum(1 for ch in line if ch.isdigit())
        if cjk_count >= 2 or (cjk_count >= 1 and digit_count >= 2):
            cleaned_lines.append(line)
            continue
        if any(token in line for token in ("票", "门票", "小时", "北部", "中部", "南部", "冲绳")):
            cleaned_lines.append(line)
    return "\n".join(cleaned_lines).strip()


def normalize_item(raw: Dict[str, Any]) -> Dict[str, Any]:
    key_map = {
        "name": "name",
        "景点名称": "name",
        "景点": "name",
        "is_popular": "is_popular",
        "热门": "is_popular",
        "是否热门": "is_popular",
        "location": "location",
        "地址": "location",
        "地点": "location",
        "region": "region",
        "区域": "region",
        "分区": "region",
        "play_time": "play_time",
        "游玩时长": "play_time",
        "建议时长": "play_time",
        "ticket_price": "ticket_price",
        "票价": "ticket_price",
        "门票": "ticket_price",
        "highlights": "highlights",
        "亮点": "highlights",
        "推荐理由": "highlights",
    }
    normalized: Dict[str, Any] = {}
    for k, v in raw.items():
        mapped = key_map.get(str(k).strip())
        if mapped:
            normalized[mapped] = v
    if "is_popular" in normalized and not isinstance(normalized["is_popular"], bool):
        val = str(normalized["is_popular"]).strip().lower()
        normalized["is_popular"] = val in {"true", "1", "yes", "是", "热门"}
    if "region" in normalized:
        region = str(normalized["region"])
        if "北" in region:
            normalized["region"] = "北部"
        elif "中" in region:
            normalized["region"] = "中部"
        elif "南" in region:
            normalized["region"] = "南部"
    return normalized


def complete_item_fields(item: Dict[str, Any]) -> Dict[str, Any]:
    completed = {
        "name": str(item.get("name", "")).strip() or "未知景点",
        "is_popular": bool(item.get("is_popular", False)),
        "location": str(item.get("location", "")).strip() or "未知地点",
        "region": str(item.get("region", "")).strip() or "中部",
        "play_time": str(item.get("play_time", "")).strip() or "未知",
        "ticket_price": str(item.get("ticket_price", "")).strip() or "未知",
        "highlights": str(item.get("highlights", "")).strip() or "未提及",
    }
    if completed["region"] not in {"北部", "中部", "南部"}:
        completed["region"] = "中部"
    return completed


def call_deepseek_for_chunk(api_key: str, chunk_text: str) -> List[Dict[str, Any]]:
    schema_hint = {
        "name": "string",
        "is_popular": "boolean",
        "location": "string",
        "region": "string, only one of: 北部/中部/南部",
        "play_time": "string",
        "ticket_price": "string",
        "highlights": "string",
    }
    prompt = (
        "你是信息抽取助手。请从以下冲绳旅游 PDF 文本中提取所有景点信息。\n"
        "仅提取与冲绳景点相关的信息；没有信息就返回空数组 []。\n"
        "输出必须是 JSON 数组，且只能输出 JSON，不要输出任何额外文字。\n"
        "字段名必须严格使用: name,is_popular,location,region,play_time,ticket_price,highlights。\n"
        f"每个对象字段必须是: {json.dumps(schema_hint, ensure_ascii=False)}。\n\n"
        f"PDF 文本如下:\n{chunk_text}"
    )
    resp = requests.post(
        DEEPSEEK_API_URL,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "model": "deepseek-chat",
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0,
        },
        timeout=120,
    )
    resp.raise_for_status()
    body = resp.json()
    content = body["choices"][0]["message"]["content"].strip()
    if content.startswith("```"):
        content = content.strip("`")
        if content.lower().startswith("json"):
            content = content[4:].strip()
    data = json.loads(content)
    if not isinstance(data, list):
        raise ValueError("模型返回的内容不是 JSON 数组")
    return data


def call_deepseek(api_key: str, pdf_text: str) -> List[OkinawaAttraction]:
    cleaned_text = clean_text_for_llm(pdf_text)
    chunks = split_text_chunks(cleaned_text or pdf_text)
    validated: List[OkinawaAttraction] = []
    seen = set()
    for chunk in chunks:
        for item in call_deepseek_for_chunk(api_key, chunk):
            if not isinstance(item, dict):
                continue
            normalized = normalize_item(item)
            completed = complete_item_fields(normalized)
            try:
                attraction = OkinawaAttraction.model_validate(completed)
            except ValidationError:
                continue
            unique_key = (attraction.name.strip(), attraction.location.strip())
            if unique_key in seen:
                continue
            seen.add(unique_key)
            validated.append(attraction)
    return validated


def main() -> None:
    parser = argparse.ArgumentParser(description="从冲绳旅游 PDF 中提取景点信息并输出 JSON")
    parser.add_argument("pdf_path", help="输入 PDF 文件路径")
    parser.add_argument(
        "--output",
        default="okinawa_attractions.json",
        help="输出 JSON 文件路径（默认: okinawa_attractions.json）",
    )
    parser.add_argument(
        "--dump-text",
        default="",
        help="可选：将抽取到的 PDF 文本另存到该路径，便于排查 OCR 质量",
    )
    args = parser.parse_args()

    pdf_path = Path(args.pdf_path).expanduser().resolve()
    output_path = Path(args.output).expanduser().resolve()
    env_path = Path(".env").resolve()
    if not pdf_path.exists():
        raise FileNotFoundError(f"输入 PDF 不存在: {pdf_path}")

    api_key = load_env_var("DEEPSEEK_API_KEY", env_path)
    pdf_text = extract_text_with_fallback(pdf_path)
    if args.dump_text:
        Path(args.dump_text).expanduser().resolve().write_text(pdf_text, encoding="utf-8")
    if not pdf_text:
        output_path.write_text("[]\n", encoding="utf-8")
        print(f"PDF 无可提取文本，已输出空结果: {output_path}")
        return
    attractions = call_deepseek(api_key, pdf_text)
    output_data = [item.model_dump() for item in attractions]
    output_path.write_text(json.dumps(output_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"提取完成，共 {len(output_data)} 条，输出文件: {output_path}")


if __name__ == "__main__":
    main()

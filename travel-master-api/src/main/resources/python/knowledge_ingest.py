import argparse
import base64
import json
import os
import sys
import uuid
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import chromadb
from bs4 import BeautifulSoup
import pytesseract
import requests
from chromadb.config import Settings
from pydantic import BaseModel
from pypdfium2 import PdfDocument
from PIL import Image, ImageEnhance, ImageOps


DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEFAULT_DEEPSEEK_CHAT_PATH = "/chat/completions"
DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"
DEFAULT_EMBEDDING_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
DEFAULT_COLLECTION_NAME = "travel_master_knowledge"


def _read_env_or_arg(name: str, default: Optional[str] = None) -> Optional[str]:
    if name in os.environ and os.environ[name].strip():
        return os.environ[name].strip()
    return default


def _as_text_from_file(path: Path) -> Tuple[str, str]:
    ext = path.suffix.lower().lstrip(".")
    if ext in {"txt", "md"}:
        return path.read_text(encoding="utf-8", errors="ignore"), ext.upper()
    raise ValueError(f"不支持的文本文件扩展名: {ext}")


def _extract_text_from_url(url: str, timeout_s: int = 20, max_chars: int = 50000) -> str:
    """
    轻量级网页正文抽取：
    - 用 BeautifulSoup 移除 script/style
    - 返回纯文本（保留基本换行）
    """
    headers = {"User-Agent": "travel-master-knowledge-ingest/1.0"}
    resp = requests.get(url, headers=headers, timeout=timeout_s)
    resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")
    for t in soup(["script", "style", "noscript"]):
        t.decompose()
    text = soup.get_text(separator="\n")
    text = "\n".join([line.strip() for line in text.splitlines() if line.strip()])
    if len(text) > max_chars:
        text = text[:max_chars]
    return text.strip()


def _render_pdf_to_images(pdf_path: Path, dpi: int, max_pages: int) -> List[Tuple[int, Image.Image]]:
    """
    返回 [(page_index_from_1, pil_image), ...]
    """
    doc = PdfDocument(str(pdf_path))
    if len(doc) == 0:
        return []

    scale = max(1, round(dpi / 72))
    images: List[Tuple[int, Image.Image]] = []
    for i, page in enumerate(doc, start=1):
        if i > max_pages:
            break
        bitmap = page.render(scale=scale).to_pil()
        images.append((i, bitmap))
    return images


def _preprocess_for_ocr(img: Image.Image) -> Image.Image:
    img = ImageOps.grayscale(img)
    img = ImageEnhance.Contrast(img).enhance(1.8)
    # 极简二值化，让 tesseract 更容易识别（对截图/扫描件通常更稳）
    thresholded = img.point(lambda p: 255 if p > 160 else 0)
    return thresholded


def _pil_to_base64_png(img: Image.Image) -> Tuple[str, str]:
    buf = BytesIO()
    img.save(buf, format="PNG", optimize=True)
    b64 = base64.b64encode(buf.getvalue()).decode("utf-8")
    return b64, "image/png"


def _call_deepseek_vision(
    api_key: str,
    base_url: str,
    chat_path: str,
    model: str,
    prompt: str,
    image_b64: str,
    mime: str = "image/png",
    timeout_s: int = 120,
) -> str:
    url = base_url.rstrip("/") + chat_path
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    payload: Dict[str, Any] = {
        "model": model,
        "temperature": 0,
        "max_tokens": 1200,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{image_b64}"}},
                ],
            }
        ],
    }

    resp = requests.post(url, headers=headers, json=payload, timeout=timeout_s)
    resp.raise_for_status()
    body = resp.json()
    return body["choices"][0]["message"]["content"].strip()


def _call_deepseek_text(
    api_key: str,
    base_url: str,
    chat_path: str,
    model: str,
    system_prompt: str,
    user_prompt: str,
    timeout_s: int = 120,
    max_tokens: int = 1200,
) -> str:
    url = base_url.rstrip("/") + chat_path
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    payload: Dict[str, Any] = {
        "model": model,
        "temperature": 0,
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }
    resp = requests.post(url, headers=headers, json=payload, timeout=timeout_s)
    resp.raise_for_status()
    body = resp.json()
    return body["choices"][0]["message"]["content"].strip()


def _ocr_with_tesseract(img: Image.Image) -> str:
    # 注意：tesseract 语言包由系统决定；我们沿用示例脚本的多语种配置
    ocr_img = _preprocess_for_ocr(img)
    txt = pytesseract.image_to_string(
        ocr_img,
        lang="chi_sim+chi_tra+jpn+eng",
        config="--oem 1 --psm 6",
    )
    return (txt or "").strip()


def _extract_text_from_images(
    images: List[Tuple[int, Image.Image]],
    deepseek_api_key: Optional[str],
    deepseek_base_url: str,
    deepseek_chat_path: str,
    deepseek_vision_model: Optional[str],
    ocr_fallback: bool = True,
) -> Tuple[str, List[Dict[str, Any]]]:
    """
    返回:
      - full_text: 所有页文本拼接
      - pages: [{page_index, method, text_len, text}, ...]
    """
    # 让 LLM 更像“识别文档文字”，不要做游离的摘要
    vision_prompt = (
        "你是 OCR 引擎。请把图片中的中文/日文/英文内容尽可能完整、按段落转成纯文本输出。"
        "不要总结，不要生成标题。尽量保留原有标点与换行。"
    )

    pages_out: List[Dict[str, Any]] = []
    full_parts: List[str] = []
    for page_index, pil_img in images:
        text = ""
        method = "OCR"
        if deepseek_api_key and deepseek_vision_model:
            try:
                b64, mime = _pil_to_base64_png(pil_img)
                text = _call_deepseek_vision(
                    api_key=deepseek_api_key,
                    base_url=deepseek_base_url,
                    chat_path=deepseek_chat_path,
                    model=deepseek_vision_model,
                    prompt=vision_prompt,
                    image_b64=b64,
                    mime=mime,
                )
                method = f"LLM_VISION({deepseek_vision_model})"
            except Exception:
                # 视觉失败不致命：回退 OCR
                if not ocr_fallback:
                    raise
                text = _ocr_with_tesseract(pil_img)
                method = "OCR_FALLBACK"
        else:
            text = _ocr_with_tesseract(pil_img)

        text = (text or "").strip()
        pages_out.append(
            {
                "page_index": page_index,
                "method": method,
                "text_len": len(text),
                "text": text,
            }
        )
        if text:
            full_parts.append(f"[page {page_index}]\n{text}")

    full_text = "\n".join(full_parts).strip()
    return full_text, pages_out


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


def clean_text_for_storage(text: str) -> str:
    # 简单的清洗：去掉空行、过短无意义段落
    cleaned_lines: List[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if len(line) < 2:
            continue
        cleaned_lines.append(line)
    return "\n".join(cleaned_lines).strip()


class DocMeta(BaseModel):
    summary: str
    keywords: List[str]


def _extract_doc_meta(
    api_key: str,
    base_url: str,
    chat_path: str,
    model: str,
    title: str,
    source_type: str,
    full_text: str,
    timeout_s: int = 120,
) -> DocMeta:
    sys_prompt = "你是知识管理助手。"
    user_prompt = (
        f"请基于以下导入内容，提取一个可用于检索的知识元数据。\n"
        f"要求：输出必须是 JSON（不要 markdown），字段为: summary, keywords。\n"
        f"summary: 3-6 句中文摘要，尽量涵盖行程规划/景点/交通/注意事项等可检索信息。\n"
        f"keywords: 8-20 个中文关键词（尽量是名词短语），可包含地名、景点名、交通方式、餐饮/门票等。\n"
        f"title: {title}\n"
        f"source_type: {source_type}\n\n"
        "导入内容如下（可能很长，仅供参考）：\n"
        f"{full_text[:12000]}"
    )
    content = _call_deepseek_text(
        api_key=api_key,
        base_url=base_url,
        chat_path=chat_path,
        model=model,
        system_prompt=sys_prompt,
        user_prompt=user_prompt,
        timeout_s=timeout_s,
        max_tokens=800,
    )
    if content.startswith("```"):
        content = content.strip("`").strip()
        if content.lower().startswith("json"):
            content = content[4:].strip()
    data = json.loads(content)
    return DocMeta.model_validate(data)


def _upsert_to_chroma(
    vector_db_path: str,
    collection_name: str,
    embedding_model: str,
    documents: List[str],
    metadatas: List[Dict[str, Any]],
    ids: List[str],
) -> None:
    """
    为了避免本地安装大模型依赖（sentence-transformers/torch），此处使用轻量字符级 hashing embedding。
    """
    def _hash_embedding(text: str, dim: int = 384) -> List[float]:
        import hashlib
        import numpy as np
        # 归一化：保留中日韩字符，转小写（对英文更稳）
        t = text.strip().lower()
        if not t:
            return [0.0] * dim

        # trigram hashing（字符级 ngram），对中日韩文本也适用
        vec = np.zeros(dim, dtype=np.float32)
        if len(t) >= 3:
            for i in range(len(t) - 2):
                gram = t[i : i + 3]
                h = int(hashlib.md5(gram.encode("utf-8")).hexdigest(), 16)
                vec[h % dim] += 1.0
        else:
            h = int(hashlib.md5(t.encode("utf-8")).hexdigest(), 16)
            vec[h % dim] += 1.0

        norm = float(np.linalg.norm(vec))
        if norm > 0:
            vec /= norm
        return vec.astype(np.float32).tolist()

    os.makedirs(vector_db_path, exist_ok=True)
    settings = Settings(anonymized_telemetry=False)
    client = chromadb.PersistentClient(path=vector_db_path, settings=settings)
    collection = client.get_or_create_collection(
        name=collection_name,
        embedding_function=None,
    )
    # Chroma 这里由我们传入 embeddings（避免 sentence-transformers 本地依赖）
    embeddings = [_hash_embedding(doc) for doc in documents]
    collection.add(
        ids=ids,
        documents=documents,
        embeddings=embeddings,
        metadatas=metadatas,
    )


def _meta_str(v: Any) -> str:
    if v is None:
        return ""
    return str(v).strip()


def _attraction_document_text(item: Dict[str, Any]) -> str:
    parts: List[str] = []
    name = _meta_str(item.get("name"))
    if name:
        parts.append(f"景点：{name}")
    for label, key in (
        ("区域", "region"),
        ("地址/位置", "location"),
        ("建议游玩", "play_time"),
        ("门票/费用", "ticket_price"),
        ("亮点", "highlights"),
        ("提示", "tips"),
    ):
        val = _meta_str(item.get(key))
        if val:
            parts.append(f"{label}：{val}")
    return "\n".join(parts).strip()


def _route_document_text(item: Dict[str, Any]) -> str:
    """一日游/多日动线等结构化路线（content_type=route）。"""
    parts: List[str] = []
    cat = _meta_str(item.get("knowledge_category"))
    if cat:
        parts.append(f"知识分类：{cat}")
    series = _meta_str(item.get("knowledge_series"))
    if series:
        parts.append(f"系列/标签：{series}")
    name = _meta_str(item.get("name"))
    if name:
        parts.append(f"路线名称：{name}")
    route_stops = _meta_str(item.get("route_stops"))
    if route_stops:
        parts.append(f"景点/动线：{route_stops}")
    for label, key in (
        ("区域", "region"),
        ("范围说明", "location"),
        ("时长", "play_time"),
        ("参考费用", "ticket_price"),
        ("亮点", "highlights"),
        ("备注", "tips"),
    ):
        val = _meta_str(item.get(key))
        if val:
            parts.append(f"{label}：{val}")
    return "\n".join(parts).strip()


def _travel_item_document_text(item: Dict[str, Any]) -> str:
    ct = _meta_str(item.get("content_type")).lower()
    if ct == "route":
        return _route_document_text(item)
    return _attraction_document_text(item)


def ingest_attractions_json(
    json_path: Path,
    user_id: str,
    vector_db_path: str,
    collection_name: str,
    embedding_model: str,
    title: str,
    source_type: str,
) -> Dict[str, Any]:
    raw = json_path.read_text(encoding="utf-8", errors="ignore")
    data = json.loads(raw)
    if not isinstance(data, list):
        raise ValueError("JSON 根节点必须是数组")

    batch_doc_id = str(uuid.uuid4())
    documents: List[str] = []
    metadatas: List[Dict[str, Any]] = []
    ids: List[str] = []

    added = 0
    for i, item in enumerate(data):
        if not isinstance(item, dict):
            continue
        doc_text = _travel_item_document_text(item)
        if not doc_text:
            continue
        name = _meta_str(item.get("name")) or f"条目{i + 1}"
        content_type = _meta_str(item.get("content_type")) or "attraction"
        kw_prefix = "冲绳,路线," if content_type == "route" else "冲绳,景点,"
        knowledge_category = _meta_str(item.get("knowledge_category"))
        documents.append(doc_text)
        ids.append(f"{batch_doc_id}:okinawa:{i}")
        metadatas.append(
            {
                "doc_id": batch_doc_id,
                "user_id": user_id,
                "title": title or json_path.name,
                "source_type": source_type,
                "file_name": json_path.name,
                "file_ext": "JSON",
                "chunk_index": i,
                "content_type": content_type,
                "knowledge_category": knowledge_category,
                "knowledge_series": _meta_str(item.get("knowledge_series")),
                "attraction_name": name,
                "region": _meta_str(item.get("region")),
                "location": _meta_str(item.get("location")),
                "play_time": _meta_str(item.get("play_time")),
                "ticket_price": _meta_str(item.get("ticket_price")),
                "doc_summary": _meta_str(item.get("highlights"))[:2000],
                "doc_keywords": kw_prefix + name + ("," + knowledge_category if knowledge_category else ""),
            }
        )
        added += 1

    if not documents:
        return {"ok": False, "error": "JSON 中无有效条目（景点或路线）", "chunks_added": 0}

    _upsert_to_chroma(
        vector_db_path=vector_db_path,
        collection_name=collection_name,
        embedding_model=embedding_model,
        documents=documents,
        metadatas=metadatas,
        ids=ids,
    )

    return {
        "ok": True,
        "doc_id": batch_doc_id,
        "file_name": json_path.name,
        "source_type": source_type,
        "chunks_added": added,
        "vector_db_path": vector_db_path,
        "collection": collection_name,
        "doc_summary": "",
        "doc_keywords": [],
        "pages": [],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="旅行知识导入：OCR/抽取 -> 文本 chunk -> 向量库落库")
    parser.add_argument("--file", required=False, help="输入文件路径（pdf/image/txt）")
    parser.add_argument("--url", default="", help="网页链接（不传 file 时可用于博客/文章）")
    parser.add_argument(
        "--json-file",
        default="",
        help="结构化 JSON 数组：景点（默认）或 content_type=route 的路线条目（与 --file/--url 互斥；无需 DeepSeek）",
    )
    parser.add_argument("--user-id", required=True)
    parser.add_argument("--source-type", default="FILE", help="FILE/IMAGE/PDF/TEXT/LINK 之类的自定义来源类型")
    parser.add_argument("--title", default="", help="可选标题，用于元数据")
    parser.add_argument("--vector-db-path", default="data/vector_db/chroma")
    parser.add_argument("--collection", default=DEFAULT_COLLECTION_NAME)
    parser.add_argument("--embedding-model", default=DEFAULT_EMBEDDING_MODEL)
    parser.add_argument("--max-pages", type=int, default=10)
    parser.add_argument("--pdf-dpi", type=int, default=300)
    parser.add_argument("--deepseek-base-url", default=DEFAULT_DEEPSEEK_BASE_URL)
    parser.add_argument("--deepseek-chat-path", default=DEFAULT_DEEPSEEK_CHAT_PATH)
    parser.add_argument("--deepseek-model", default=DEFAULT_DEEPSEEK_MODEL)
    parser.add_argument("--deepseek-vision-model", default="", help="如不填写则跳过 LLM 视觉 OCR，直接走 tesseract")
    parser.add_argument("--deepseek-api-key", default="", help="如不填则从环境变量 DEEPSEEK_API_KEY 读取")

    args = parser.parse_args()

    json_file = (args.json_file or "").strip()
    if json_file:
        json_path = Path(json_file).expanduser().resolve()
        if not json_path.exists():
            raise FileNotFoundError(f"JSON 文件不存在: {json_path}")
        file_path = Path(args.file) if args.file else None
        url = (args.url or "").strip()
        if file_path or url:
            raise ValueError("--json-file 不能与 --file 或 --url 同时使用")
        out = ingest_attractions_json(
            json_path=json_path,
            user_id=args.user_id,
            vector_db_path=args.vector_db_path,
            collection_name=args.collection,
            embedding_model=args.embedding_model,
            title=args.title.strip(),
            source_type=(args.source_type or "JSON_ATTRACTIONS").strip() or "JSON_ATTRACTIONS",
        )
        print(json.dumps(out, ensure_ascii=False))
        if not out.get("ok"):
            sys.exit(3)
        return

    deepseek_api_key = args.deepseek_api_key.strip() or _read_env_or_arg("DEEPSEEK_API_KEY")
    if not deepseek_api_key:
        print(json.dumps({"ok": False, "error": "未配置 DEEPSEEK_API_KEY"}, ensure_ascii=False))
        sys.exit(2)

    file_path = Path(args.file) if args.file else None
    url = (args.url or "").strip()
    if not file_path and not url:
        raise ValueError("未提供 --file 或 --url")
    if file_path and not file_path.exists():
        raise FileNotFoundError(f"文件不存在: {file_path}")

    doc_id = str(uuid.uuid4())
    vector_db_path = args.vector_db_path

    file_name_for_meta = ""
    file_ext_for_meta = ""
    source_type = args.source_type or "FILE"
    pages_out: List[Dict[str, Any]] = []

    full_text = ""
    if url:
        file_name_for_meta = args.title.strip() or url
        file_ext_for_meta = "URL"
        full_text = _extract_text_from_url(url)
        source_type = args.source_type or "LINK"
        pages_out = [
            {
                "page_index": 1,
                "method": "URL_TEXT",
                "text_len": len(full_text),
                "text": full_text,
            }
        ]
    else:
        ext = file_path.suffix.lower().lstrip(".")
        ext_upper = ext.upper()

        file_name_for_meta = file_path.name
        file_ext_for_meta = ext_upper

        if ext in {"pdf"}:
            images = _render_pdf_to_images(file_path, dpi=args.pdf_dpi, max_pages=args.max_pages)
            full_text, pages_out = _extract_text_from_images(
                images=images,
                deepseek_api_key=deepseek_api_key,
                deepseek_base_url=args.deepseek_base_url,
                deepseek_chat_path=args.deepseek_chat_path,
                deepseek_vision_model=args.deepseek_vision_model.strip() or None,
                ocr_fallback=True,
            )
            source_type = args.source_type or "PDF"
        elif ext in {"png", "jpg", "jpeg", "webp", "bmp"}:
            img = Image.open(str(file_path))
            images = [(1, img)]
            full_text, pages_out = _extract_text_from_images(
                images=images,
                deepseek_api_key=deepseek_api_key,
                deepseek_base_url=args.deepseek_base_url,
                deepseek_chat_path=args.deepseek_chat_path,
                deepseek_vision_model=args.deepseek_vision_model.strip() or None,
                ocr_fallback=True,
            )
            source_type = args.source_type or "IMAGE"
        elif ext in {"txt", "md"}:
            full_text, _ = _as_text_from_file(file_path)
            pages_out = [{"page_index": 1, "method": "TEXT_FILE", "text_len": len(full_text), "text": full_text}]
            source_type = "TEXT"
        else:
            raise ValueError(f"不支持的文件类型: {ext}")

    full_text = clean_text_for_storage(full_text)
    if not full_text:
        print(json.dumps({"ok": False, "doc_id": doc_id, "chunks_added": 0, "error": "未抽取到文本"}, ensure_ascii=False))
        sys.exit(3)

    # 只做一次 doc meta，避免为每个 chunk 都调用 LLM
    try:
        doc_meta = _extract_doc_meta(
            api_key=deepseek_api_key,
            base_url=args.deepseek_base_url,
            chat_path=args.deepseek_chat_path,
            model=args.deepseek_model,
            title=args.title.strip() or file_name_for_meta,
            source_type=source_type,
            full_text=full_text,
        )
    except Exception:
        doc_meta = DocMeta(summary="", keywords=[])

    chunks = split_text_chunks(full_text)
    if not chunks:
        print(json.dumps({"ok": False, "doc_id": doc_id, "chunks_added": 0, "error": "切分后为空"}, ensure_ascii=False))
        sys.exit(4)

    documents: List[str] = []
    metadatas: List[Dict[str, Any]] = []
    ids: List[str] = []

    for i, chunk in enumerate(chunks):
        chunk_id = f"{doc_id}:{i}"
        documents.append(chunk)
        metadatas.append(
            {
                "doc_id": doc_id,
                "user_id": args.user_id,
                "title": args.title.strip() or file_name_for_meta,
                "source_type": source_type,
                "file_name": file_name_for_meta,
                "file_ext": file_ext_for_meta,
                "chunk_index": i,
                "doc_summary": doc_meta.summary,
                "doc_keywords": ",".join(doc_meta.keywords),
            }
        )
        ids.append(chunk_id)

    _upsert_to_chroma(
        vector_db_path=vector_db_path,
        collection_name=args.collection,
        embedding_model=args.embedding_model,
        documents=documents,
        metadatas=metadatas,
        ids=ids,
    )

    display_name = file_name_for_meta if url else (file_path.name if file_path else "")
    out = {
        "ok": True,
        "doc_id": doc_id,
        "file_name": display_name,
        "source_type": source_type,
        "chunks_added": len(chunks),
        "vector_db_path": vector_db_path,
        "collection": args.collection,
        "doc_summary": doc_meta.summary,
        "doc_keywords": doc_meta.keywords,
        "pages": [{"page_index": p["page_index"], "method": p["method"], "text_len": p["text_len"]} for p in pages_out],
    }
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()


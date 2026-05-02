"""
从 Chroma 持久化库中按与 knowledge_ingest 相同的 hashing embedding 做相似度检索。
输出一行 JSON 到 stdout，供 Java RAG 服务解析。
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

import chromadb
import numpy as np
from chromadb.config import Settings


def hash_embedding(text: str, dim: int = 384) -> List[float]:
    t = (text or "").strip().lower()
    if not t:
        return [0.0] * dim
    vec = np.zeros(dim, dtype=np.float32)
    if len(t) >= 3:
        for i in range(len(t) - 2):
            gram = t[i : i + 3]
            h = int(hashlib.md5(gram.encode("utf-8")).hexdigest(), 16)
            vec[h % dim] += 1.0
    else:
        h = int(hashlib.md5(t.encode("utf-8")).hexdigest(), 16)
        vec[h % dim] += 1.0
    n = float(np.linalg.norm(vec))
    if n > 0:
        vec /= n
    return vec.astype(np.float32).tolist()


def main() -> None:
    parser = argparse.ArgumentParser(description="Chroma 向量检索（hash embedding，与入库一致）")
    parser.add_argument("--query", required=True)
    parser.add_argument("--vector-db-path", required=True)
    parser.add_argument("--collection", required=True)
    parser.add_argument("--n-results", type=int, default=5)
    args = parser.parse_args()

    db_path = str(Path(args.vector_db_path).expanduser().resolve())
    q = (args.query or "").strip()
    if not q:
        print(json.dumps({"ok": False, "error": "query 为空", "hits": []}, ensure_ascii=False))
        sys.exit(2)

    settings = Settings(anonymized_telemetry=False)
    client = chromadb.PersistentClient(path=db_path, settings=settings)
    try:
        col = client.get_collection(args.collection)
    except Exception as e:
        print(json.dumps({"ok": False, "error": f"获取 collection 失败: {e}", "hits": []}, ensure_ascii=False))
        sys.exit(3)

    emb = hash_embedding(q)
    try:
        res = col.query(query_embeddings=[emb], n_results=int(args.n_results))
    except Exception as e:
        print(json.dumps({"ok": False, "error": f"query 失败: {e}", "hits": []}, ensure_ascii=False))
        sys.exit(4)

    ids = (res.get("ids") or [[]])[0]
    documents = (res.get("documents") or [[]])[0]
    metadatas = (res.get("metadatas") or [[]])[0]
    distances: List[Optional[float]] = (res.get("distances") or [[]])[0]
    if not ids:
        print(json.dumps({"ok": True, "hits": []}, ensure_ascii=False))
        return

    hits: List[Dict[str, Any]] = []
    for i, doc_id in enumerate(ids):
        doc = documents[i] if i < len(documents) else ""
        meta = metadatas[i] if i < len(metadatas) else {}
        dist = distances[i] if i < len(distances) else None
        hits.append(
            {
                "id": doc_id,
                "document": doc,
                "metadata": meta or {},
                "distance": dist,
            }
        )

    print(json.dumps({"ok": True, "hits": hits}, ensure_ascii=False))


if __name__ == "__main__":
    main()

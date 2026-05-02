import { useEffect, useMemo, useState } from "react";

const STORAGE_KEY = "travel-master-chats-v1";
// “回合”按：一次用户输入 + 一次助手回复计为 1 回合
const MAX_TURNS = 4;
// 初始会话里有 1 条欢迎语（assistant），所以总消息数上限约等于 1 + 2 * MAX_TURNS
const MAX_MESSAGES = 1 + 2 * MAX_TURNS;

function trimMessages(messages) {
  if (!Array.isArray(messages)) return messages;
  return messages.length > MAX_MESSAGES ? messages.slice(-MAX_MESSAGES) : messages;
}

function createEmptySession() {
  const id = crypto.randomUUID();
  return {
    id,
    title: "新对话",
    updatedAt: Date.now(),
    messages: [
      {
        id: crypto.randomUUID(),
        role: "assistant",
        content: "嗨，我是 Travel Master～\n说说你想去哪、玩几天，我帮你记下来并问问后台。"
      }
    ]
  };
}

function loadSessions() {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return [createEmptySession()];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed) && parsed.length > 0) return parsed;
  } catch (e) {
    console.error("解析历史会话失败", e);
  }
  return [createEmptySession()];
}

export default function App() {
  const [sessions, setSessions] = useState(loadSessions);
  const [activeSessionId, setActiveSessionId] = useState(loadSessions()[0].id);
  const [inputText, setInputText] = useState("");
  const [loading, setLoading] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
  }, [sessions]);

  const activeSession = useMemo(
    () => sessions.find((s) => s.id === activeSessionId) ?? sessions[0],
    [sessions, activeSessionId]
  );

  async function handleSend() {
    const text = inputText.trim();
    if (!text || !activeSession) return;

    setInputText("");
    const userMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content: text
    };

    setSessions((prev) =>
      prev.map((session) =>
        session.id === activeSession.id
          ? {
              ...session,
              title: session.title === "新对话" ? text.slice(0, 20) : session.title,
              updatedAt: Date.now(),
              messages: trimMessages([...session.messages, userMessage])
            }
          : session
      )
    );

    setLoading(true);
    try {
      const response = await fetch("/api/v1/user-input/text", {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          userId: activeSession.id,
          message: text
        })
      });

      if (!response.ok) {
        throw new Error(`接口调用失败: ${response.status}`);
      }

      const data = await response.json();
      const citeLines = (data.ragCitations || [])
        .map((c, i) => {
          const name =
            c.metadata?.attraction_name ||
            c.metadata?.title ||
            c.metadata?.name ||
            `片段${i + 1}`;
          return `${i + 1}. ${name}`;
        })
        .join("\n");

      let content;
      if (data.intent === "QUESTION") {
        const answer =
          typeof data.ragAnswer === "string" && data.ragAnswer.trim()
            ? data.ragAnswer.trim()
            : "（未返回回答正文）";
        content = `${answer}\n\n—— 引用条目 ——\n${citeLines || "（无）"}`;
      } else {
        const draft =
          typeof data.ragAnswer === "string" && data.ragAnswer.trim()
            ? `\n\n【回复正文】\n${data.ragAnswer.trim()}`
            : "";
        const cites =
          (data.ragCitations || []).length > 0
            ? `\n\n—— 素材条目 ——\n${citeLines || "（无）"}`
            : "";
        content = `意图：${data.intent}\n路由：${data.routeToAgent}\n说明：${data.reasoning}${draft}${cites}`;
      }

      const assistantMessage = {
        id: crypto.randomUUID(),
        role: "assistant",
        content
      };

      setSessions((prev) =>
        prev.map((session) =>
          session.id === activeSession.id
            ? {
                ...session,
                updatedAt: Date.now(),
                messages: trimMessages([...session.messages, assistantMessage])
              }
            : session
        )
      );
    } catch (error) {
      const fallback = {
        id: crypto.randomUUID(),
        role: "assistant",
        content: `接口调用异常：${error.message}`
      };
      setSessions((prev) =>
        prev.map((session) =>
          session.id === activeSession.id
            ? {
                ...session,
                updatedAt: Date.now(),
                messages: trimMessages([...session.messages, fallback])
              }
            : session
        )
      );
    } finally {
      setLoading(false);
    }
  }

  async function handleUpload() {
    if (!selectedFile || !activeSession) return;

    setUploading(true);
    const file = selectedFile;
    setSelectedFile(null);

    try {
      const ext = (file.name.split(".").pop() || "").toLowerCase();
      const sourceType =
        ext === "pdf"
          ? "PDF"
          : ["png", "jpg", "jpeg", "webp", "bmp", "gif"].includes(ext)
          ? "IMAGE"
          : "FILE";

      const form = new FormData();
      form.append("userId", activeSession.id);
      form.append("title", file.name);
      form.append("sourceType", sourceType);
      form.append("file", file);

      const response = await fetch("/api/v1/knowledge/ingest", {
        method: "POST",
        body: form
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(`接口调用失败: ${response.status} ${errText}`);
      }

      const data = await response.json();

      const assistantMessage = {
        id: crypto.randomUUID(),
        role: "assistant",
        content: `知识导入完成。\nfile: ${data.fileName}\ndocId: ${data.docId}\nchunks: ${data.chunksAdded}`
      };

      setSessions((prev) =>
        prev.map((session) =>
          session.id === activeSession.id
            ? {
                ...session,
                updatedAt: Date.now(),
                messages: trimMessages([...session.messages, assistantMessage])
              }
            : session
        )
      );
    } catch (error) {
      const fallback = {
        id: crypto.randomUUID(),
        role: "assistant",
        content: `上传失败：${error.message}`
      };
      setSessions((prev) =>
        prev.map((session) =>
          session.id === activeSession.id
            ? {
                ...session,
                updatedAt: Date.now(),
                messages: trimMessages([...session.messages, fallback])
              }
            : session
        )
      );
    } finally {
      setUploading(false);
    }
  }

  function createSession() {
    const newSession = createEmptySession();
    setSessions((prev) => [newSession, ...prev]);
    setActiveSessionId(newSession.id);
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">
          Travel Master
          <span className="brand-sub">轻松旅行小助手</span>
        </div>
        <button type="button" className="new-chat-btn" onClick={createSession}>
          ＋ 新建对话
        </button>
        <div className="session-list">
          {sessions
            .slice()
            .sort((a, b) => b.updatedAt - a.updatedAt)
            .map((session) => (
              <button
                type="button"
                key={session.id}
                className={`session-item ${session.id === activeSessionId ? "active" : ""}`}
                onClick={() => setActiveSessionId(session.id)}
              >
                <span className="session-title">{session.title}</span>
              </button>
            ))}
        </div>
      </aside>

      <main className="chat-panel">
        <header className="chat-header">
          当前会话：<strong>{activeSession?.title ?? "…"}</strong>
        </header>
        <div className="messages">
          {activeSession?.messages.map((message) => (
            <div key={message.id} className={`message-row ${message.role}`}>
              <div className="message-bubble">{message.content}</div>
            </div>
          ))}
          {loading ? (
            <div className="message-row assistant">
              <div className="message-bubble loading-bubble">稍等哦，正在帮你问后台…</div>
            </div>
          ) : null}
        </div>

        <div className="composer">
          <div className="upload-row">
            <input
              type="file"
              accept="application/pdf,image/*"
              onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
              disabled={uploading}
            />
            <button type="button" onClick={handleUpload} disabled={uploading || !selectedFile}>
              {uploading ? "上传中…" : "上传攻略"}
            </button>
          </div>
          <textarea
            placeholder="例如：冲绳玩三天怎么安排？"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            rows={3}
          />
          <div className="send-row">
            <button type="button" onClick={handleSend} disabled={loading || !inputText.trim()}>
              发送
            </button>
          </div>
        </div>
      </main>
    </div>
  );
}

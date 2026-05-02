
开发日志：

- 2026-04-27 feat：增加调度员Dispatcher agent
- 2026-04-27 test：完成 Dispatcher 三个基础用例联调（QUESTION、REPLAN_ROUTE、OTHER）
- 2026-04-27 chore：补充并同步开发日志到 README 与 CHANGELOG

- 2026-05-02 feat：路线规划师（Planner）——核心大脑，不直接做百科问答；按目的地与天数从 Chroma 多路检索素材后生成按天行程草案。新增 `PlannerService`、`PlanParameterExtractorService`；`RagService.retrieveCitations` 供多路检索；`POST /api/v1/planner/draft`（`PlanDraftRequest`/`PlanDraftResponse`）；配置项 `knowledge.planner.*`（`application.yml`）。
- 2026-05-02 feat：调度意图扩展——新增 `PLAN_ITINERARY`（与 `REPLAN_ROUTE` 均路由 `route-planner-agent`）；`TextInputService` 在规划类意图下抽取目的地/天数后调用规划师，草案与素材写入原 `ragAnswer`/`ragCitations` 字段。
- 2026-05-02 feat：知识入库——`knowledge_ingest.py` 支持 `content_type=route` 的路线条目（正文模板与元数据 `knowledge_category`、`knowledge_series` 等）；景点 JSON 与路线 JSON 共用 `--json-file` 入口。
- 2026-05-02 data：新增 `data/okinawa_routes_novice_ab.json`（路线推荐｜新手首选 A/B 线：总览 + A 线北部全景 + B 线玻璃船/福木林道/水族馆/美国村），供导入向量库。
- 2026-05-02 schema：`data/schema.py` 增加 `OkinawaRouteKnowledge` 模型，与路线入库字段对齐。
- 2026-05-02 chore：`.gitignore` 忽略临时 venv（`travel-master-api/.ingest_venv/`）、Maven `target/`、本地 Chroma（`data/vector_db/`）、前端 `dist/`、`.vscode/`。
- 2026-05-02 ui：`travel-master-web` 白底轻松风格，站酷黄油体 + VT323 标题感、粗描边与块状阴影（像素块 UI）；侧栏品牌区与当前会话标题；规划类意图在聊天中展示 `ragAnswer` 与素材引用。

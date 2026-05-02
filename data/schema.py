from pydantic import BaseModel, Field


class OkinawaRouteKnowledge(BaseModel):
    """入库用路线条目（与 knowledge_ingest.py 中 content_type=route 对应）。"""

    content_type: str = Field(default="route", description="固定为 route")
    knowledge_category: str = Field(description="大类，例如：路线推荐")
    knowledge_series: str = Field(default="", description="系列或标签，例如：新手首选A、B线")
    name: str = Field(description="路线名称或总览标题")
    route_stops: str = Field(default="", description="景点顺序，用→连接")
    region: str = Field(default="", description="区域，如 北部、北部·中部")
    location: str = Field(default="", description="范围说明")
    play_time: str = Field(default="", description="时长")
    ticket_price: str = Field(default="", description="参考费用")
    highlights: str = Field(default="", description="亮点")
    tips: str = Field(default="", description="备注")


class OkinawaAttraction(BaseModel):
    name: str = Field(description="名称，例如：海洋博公园")
    is_popular: bool = Field(description="是否是热门景点（通常攻略里大篇幅介绍的即为True）")
    location: str = Field(description="具体地点/地址")
    region: str = Field(description="所属区域：只能是 '北部'、'中部' 或 '南部' 之一")
    play_time: str = Field(description="建议游玩时长，例如：2-3小时")
    ticket_price: str = Field(description="票价信息，例如：成人2180日元，6岁以下免费")
    highlights: str = Field(description="亮点介绍，用一句话概括最吸引人的地方")
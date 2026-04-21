from pydantic import BaseModel, Field


class OkinawaAttraction(BaseModel):
    name: str = Field(description="名称，例如：海洋博公园")
    is_popular: bool = Field(description="是否是热门景点（通常攻略里大篇幅介绍的即为True）")
    location: str = Field(description="具体地点/地址")
    region: str = Field(description="所属区域：只能是 '北部'、'中部' 或 '南部' 之一")
    play_time: str = Field(description="建议游玩时长，例如：2-3小时")
    ticket_price: str = Field(description="票价信息，例如：成人2180日元，6岁以下免费")
    highlights: str = Field(description="亮点介绍，用一句话概括最吸引人的地方")
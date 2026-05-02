package com.travelmaster.api.dto;

public enum IntentType {
    QUESTION,
    /** 根据目的地与天数等生成行程草案（规划师） */
    PLAN_ITINERARY,
    REPLAN_ROUTE,
    OTHER
}

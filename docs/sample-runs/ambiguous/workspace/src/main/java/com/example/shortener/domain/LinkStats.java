package com.example.shortener.domain;

import java.util.List;

public record LinkStats(String code, long totalClicks, List<DayCount> daily, List<HostCount> topReferrers,
        long uniqueVisitors) {

    public record DayCount(String date, long clicks) {
    }

    public record HostCount(String host, long clicks) {
    }
}

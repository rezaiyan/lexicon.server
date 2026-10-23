package com.alirezaiyan.vokab.server.analytics

interface DifficultWordProjection {
    val wordId: Long
    val wordText: String
    val wordTranslation: String
    val sourceLanguage: String
    val targetLanguage: String
    val total: Long
    val errors: Long
}

interface MostReviewedWordProjection {
    val wordId: Long
    val wordText: String
    val wordTranslation: String
    val total: Long
}

interface AccuracyByLevelProjection {
    val level: Int
    val total: Long
    val correct: Long
}

interface HourlyAccuracyProjection {
    val hour: Int
    val total: Long
    val correct: Long
}

interface DayOfWeekAccuracyProjection {
    val dayOfWeek: Int
    val total: Long
    val correct: Long
}

interface HeatmapDayProjection {
    val day: String
    val count: Long
}

interface LevelTransitionProjection {
    val fromLevel: Int
    val toLevel: Int
    val count: Long
}

interface MasteredWordProjection {
    val wordId: Long
    val wordText: String
    val wordTranslation: String
    val masteredAt: Long
}

interface LanguagePairStatsProjection {
    val sourceLanguage: String
    val targetLanguage: String
    val total: Long
    val correct: Long
    val uniqueWords: Long
}

interface MonthlyStatsProjection {
    val yr: Int
    val mo: Int
    val total: Long
    val correct: Long
}

interface ResponseTimeTrendProjection {
    val yr: Int
    val wk: Int
    val avgMs: Double
}

interface ComebackWordProjection {
    val wordId: Long
    val wordText: String
    val wordTranslation: String
}

interface DailyEventStatsProjection {
    val day: String
    val uniqueWords: Long
    val leveledUp: Long
    val leveledDown: Long
}

interface ReviewFactProjection {
    val wordId: Long
    val wordText: String
    val rating: Int
    val previousLevel: Int
    val newLevel: Int
    val reviewedAt: Long
}

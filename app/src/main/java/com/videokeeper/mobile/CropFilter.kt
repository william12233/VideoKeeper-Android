package com.videokeeper.mobile

/** Quick filters for the crop page; the filter never changes selection state. */
enum class CropFilter(val label: String) {
    ALL("全部"),
    PENDING("待分析"),
    RUNNING("分析中"),
    REVIEW("待確認"),
    NO_CROP("不需裁切"),
    CONFIRMED("已確認／裁切"),
    UNSELECTED("未勾選"),
    FAILED("失敗／不支援")
}

internal fun matchesCropFilter(entry: VideoEntry, filter: CropFilter): Boolean =
    matchesCropFilter(entry.cropState, entry.crop, filter)

internal fun matchesCropFilter(
    state: AnalysisState,
    crop: CropDecision,
    filter: CropFilter
): Boolean = when (filter) {
    CropFilter.ALL -> true
    CropFilter.PENDING -> state == AnalysisState.PENDING
    CropFilter.RUNNING -> state == AnalysisState.RUNNING
    CropFilter.REVIEW -> state == AnalysisState.NEEDS_REVIEW
    CropFilter.NO_CROP -> state == AnalysisState.READY && !crop.cropped
    CropFilter.CONFIRMED -> crop.cropped && crop.selected
    CropFilter.UNSELECTED -> crop.cropped && !crop.selected &&
        state != AnalysisState.PENDING && state != AnalysisState.RUNNING
    CropFilter.FAILED -> state == AnalysisState.FAILED || state == AnalysisState.UNSUPPORTED
}

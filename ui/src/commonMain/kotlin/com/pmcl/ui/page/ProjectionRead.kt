package com.pmcl.ui.page

data class ProjectionKind(
    val name: String,
    val count: Int
)

data class ProjectionInfo(
    val name: String,
    val width: Int,
    val height: Int,
    val length: Int,
    val blocks: Int,
    val kinds: List<ProjectionKind> = emptyList()
)

expect fun readProjection(path: String): ProjectionInfo?

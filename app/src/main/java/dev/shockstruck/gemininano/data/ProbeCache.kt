package dev.shockstruck.gemininano.data

import dev.shockstruck.gemininano.model.SummarizationProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last summarization probe result, shared between Overview and Settings ("reset probes"). */
class ProbeCache {

    private val _probe = MutableStateFlow<SummarizationProbeResult?>(null)
    val probe: StateFlow<SummarizationProbeResult?> = _probe.asStateFlow()

    fun store(result: SummarizationProbeResult) {
        _probe.value = result
    }

    fun clear() {
        _probe.value = null
    }
}

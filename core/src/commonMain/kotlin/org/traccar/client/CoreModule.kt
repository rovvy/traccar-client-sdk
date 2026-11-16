package org.traccar.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun coreModule(): Module = module {
    single { ConfigStore(get()) }
    single<PositionQueue> { DatabaseQueue(get()) }
    single<Flow<Position>> { get<LocationSource>().positions }
    single<StateFlow<State>> { get<StateStore>().state }
    single { ComponentCoroutineScope() }

    single<Uploader> { HttpUploader(get(), get()) }
    single { LocationFilter(get(), get()) }

    single {
        TrackerEngine(
            stateStore = get(),
            queue = get(),
            network = get(),
            locationSource = get(),
            signalSources = get(),
            processors = listOf(get<LocationFilter>(), get()),
            uploader = get(),
            buffer = get<Config>().buffer,
            scope = get<ComponentCoroutineScope>(),
            // the heartbeat trigger exists only with stop detection (PlatformModule), and #26 needs stop detection
            activeHeartbeat = get<Config>().location.let { if (it.stopDetection) it.heartbeatIntervalSeconds.seconds else Duration.ZERO },
        )
    }

    single {
        Tracker(
            config = get(),
            stateStore = get(),
            locationSource = get(),
            batteryProcessor = get(),
            uploader = get(),
            componentScope = get(),
        )
    }
}

internal expect fun platformModule(): Module

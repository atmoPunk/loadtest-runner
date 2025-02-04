package kvas

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.onClose
import org.koin.dsl.module

val applicationModule = module {
    singleOf(::VMRepositoryImpl) {
        bind<VMRepository>()
        createdAtStart()
        onClose { repository -> repository?.close() }
    }
    singleOf(::LauncherImpl) {
        bind<Launcher>()
        createdAtStart()
        onClose { launcher -> launcher?.close() }
    }
    singleOf(::LogStorageImpl) {
        bind<LogStorage>()
        createdAtStart()
        onClose { storage -> storage?.close() }
    }
}
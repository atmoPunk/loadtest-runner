package kvas

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.createdAtStart
import org.koin.dsl.module

val applicationModule = module {
    singleOf(::LauncherImpl) {
        bind<Launcher>()
        createdAtStart()
    }
}
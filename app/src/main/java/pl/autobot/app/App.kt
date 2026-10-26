package pl.autobot.app

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import pl.autobot.app.data.PickedGesture
import pl.autobot.app.data.Repository
import pl.autobot.app.engine.BotEngine
import pl.autobot.app.service.BotAccessibilityService

class App : Application() {
    lateinit var repo: Repository
        private set
    lateinit var engine: BotEngine
        private set

    /** Ostatnia gestura pobrana z ekranu pływającym przyciskiem – odbiera ją otwarty dialog edycji bloku. */
    val coordPick = MutableStateFlow<PickedGesture?>(null)

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        engine = BotEngine(repo) { BotAccessibilityService.instance }
    }
}

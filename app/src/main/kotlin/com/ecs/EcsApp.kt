package com.ecs

import android.app.Application
import com.ecs.agent.AgentClient
import com.ecs.agent.Annotator
import com.ecs.agent.PaperScanner
import com.ecs.agent.Reporter
import com.ecs.agent.TreeGenerator
import com.ecs.agent.TreeMaintainer
import com.ecs.agent.Verifier
import com.ecs.data.backup.BackupManager
import com.ecs.data.db.AppDatabase
import com.ecs.data.repo.RecordRepository
import com.ecs.data.repo.Settings
import com.ecs.data.repo.TreeStore
import kotlinx.coroutines.flow.first

class EcsApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

/** 手写 DI：依赖不多，一个容器比引一套框架划算。 */
class Container(app: Application) {
    val settings = Settings(app)
    val treeStore = TreeStore(app)
    private val backup = BackupManager(app)
    val repository = RecordRepository(
        dao = AppDatabase.get(app).records(),
        treeStore = treeStore,
        settings = settings,
        backup = backup,
    )
    val client = AgentClient(
        apiKeyProvider = { settings.apiKey.first() },
        modelProvider = { settings.model.first() },
    )
    val treeGenerator = TreeGenerator(client)
    val annotator = Annotator(client)
    val verifier = Verifier(client)
    val reporter = Reporter(client)
    val maintainer = TreeMaintainer(client)
    val scanner = PaperScanner(client)
}

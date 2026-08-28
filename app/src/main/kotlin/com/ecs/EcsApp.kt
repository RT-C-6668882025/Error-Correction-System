package com.ecs

import android.app.Application
import com.ecs.agent.AgentClient
import com.ecs.agent.Annotator
import com.ecs.agent.PaperScanner
import com.ecs.agent.PromptProvider
import com.ecs.agent.Reporter
import com.ecs.agent.TreeGenerator
import com.ecs.agent.TreeMaintainer
import com.ecs.agent.Verifier
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.Protocol
import com.ecs.data.backup.BackupManager
import com.ecs.data.db.AppDatabase
import com.ecs.data.repo.PromptStore
import com.ecs.data.repo.RecordRepository
import com.ecs.data.repo.Settings
import com.ecs.data.repo.TreeStore
import com.ecs.data.update.UpdateChecker
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
    val promptStore = PromptStore(app)
    val updateChecker = UpdateChecker(app)
    private val backup = BackupManager(app)

    val repository = RecordRepository(
        dao = AppDatabase.get(app).records(),
        treeStore = treeStore,
        settings = settings,
        backup = backup,
    )

    private val prompts = PromptProvider { slot -> promptStore.text(slot) }

    val client = AgentClient { role ->
        val (endpointId, modelId) = when (role) {
            AgentClient.Role.TEXT -> settings.textEndpoint.first() to settings.textModel.first()
            AgentClient.Role.VISION -> settings.visionEndpoint.first() to settings.visionModel.first()
        }
        AgentClient.Config(
            endpoint = settings.endpointById(endpointId) ?: fallbackEndpoint(endpointId),
            modelId = modelId,
        )
    }

    /** 选中的端点被删掉时不崩，报一个能看懂的错。 */
    private fun fallbackEndpoint(id: String): ApiEndpoint =
        BuiltInEndpoints.byId(id) ?: ApiEndpoint(
            id = id,
            name = "未配置的端点「$id」",
            baseUrl = "",
            protocol = Protocol.OPENAI,
        )

    val treeGenerator = TreeGenerator(client, prompts)
    val annotator = Annotator(client, prompts)
    val verifier = Verifier(client, prompts)
    val reporter = Reporter(client, prompts)
    val maintainer = TreeMaintainer(client, prompts)
    val scanner = PaperScanner(client, prompts)
}

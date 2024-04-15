package cc.unitmesh.devti.custom.tasks

import cc.unitmesh.cf.core.llms.LlmMsg
import cc.unitmesh.cf.core.parser.MarkdownCode
import cc.unitmesh.devti.AutoDevBundle
import cc.unitmesh.devti.llms.LlmFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.coroutines.flow.*

class FileGenerateTask(@JvmField val project: Project, val messages: List<LlmMsg.ChatMessage>, val fileName: String?) :
    Task.Backgroundable(project, AutoDevBundle.message("intentions.request.background.process.title")) {
    private val projectRoot = project.guessProjectDir()!!

    override fun run(indicator: ProgressIndicator) {
        val requestPrompt = messages.filter { it.role == LlmMsg.ChatRole.User }.joinToString("\n") { it.content }
        val systemPrompt = messages.filter { it.role == LlmMsg.ChatRole.System }.joinToString("\n") { it.content }

        val stream =
            LlmFactory().create(project).stream(requestPrompt, systemPrompt, false)

        var result = ""
        runBlocking {
            stream.cancellable().collect {
                result += it
            }
        }

        val inferFileName = if (fileName == null) {
            val language = MarkdownCode.parse(result).language
            val timestamp = System.currentTimeMillis()
            "output-" + timestamp + if (language.isEmpty()) ".txt" else ".$language"
        } else {
            fileName
        }

        val file = project.guessProjectDir()!!.toNioPath().resolve(inferFileName).toFile()
        if (!file.exists()) {
            file.createNewFile()
        }

        file.writeText(result)
        refreshAndOpenInEditor(Path(projectRoot.path), projectRoot)
    }

    protected fun refreshAndOpenInEditor(file: Path, parentDir: VirtualFile) {
        runBlocking {
            ProgressManager.getInstance().run(object : Modal(project, "Refreshing Project Model", true) {
                override fun run(indicator: ProgressIndicator) {
                    repeat(5) {
                        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file)
                        if (virtualFile == null) {
                            VfsUtil.markDirtyAndRefresh(true, true, true, parentDir)
                        } else {
                            try {
                                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                                return
                            } catch (e: Exception) {
                                //
                            }
                        }
                    }
                }
            })
        }
    }
}
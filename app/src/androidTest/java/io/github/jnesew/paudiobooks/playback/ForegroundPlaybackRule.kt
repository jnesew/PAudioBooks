package io.github.jnesew.paudiobooks.playback

import androidx.test.core.app.ActivityScenario
import io.github.jnesew.paudiobooks.ui.MainActivity
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** These tests model a user pressing Play, which needs a visible app or existing FGS on API35+. */
class ForegroundPlaybackRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { check(!it.isFinishing) }
                base.evaluate()
            }
        }
    }
}

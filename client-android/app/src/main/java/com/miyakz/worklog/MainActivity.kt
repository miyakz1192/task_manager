package com.miyakz.worklog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miyakz.worklog.ui.today.TodayScreen
import com.miyakz.worklog.ui.today.TodayViewModel
import com.miyakz.worklog.ui.today.TodayViewModelFactory
import com.miyakz.worklog.ui.theme.WorkLogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WorkLogApp

        setContent {
            WorkLogTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel = viewModel<TodayViewModel>(factory = TodayViewModelFactory(app))
                    TodayScreen(viewModel = viewModel)
                }
            }
        }
    }
}

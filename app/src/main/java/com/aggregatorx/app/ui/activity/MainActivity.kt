package com.aggregatorx.app.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aggregatorx.app.ui.screens.SearchScreen
import com.aggregatorx.app.ui.viewmodel.SearchViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AggregatorApp()
        }
    }

    @Composable
    fun AggregatorApp() {
        val navController = rememberNavController()
        val searchViewModel: SearchViewModel = hiltViewModel()

        NavHost(navController = navController, startDestination = "search") {
            composable("search") {
                SearchScreen(
                    viewModel = searchViewModel,
                    onOpenBrowser = { url ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    },
                    onWatchVideo = { videoUrl ->
                        val intent = Intent(this@MainActivity, VideoPlayerActivity::class.java).apply {
                            putExtra("VIDEO_URL", videoUrl)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

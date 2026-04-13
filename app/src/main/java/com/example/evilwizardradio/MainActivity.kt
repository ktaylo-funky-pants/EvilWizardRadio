package com.example.evilwizardradio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.evilwizardradio.ui.theme.EvilWizardRadioTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class Tab {
    MUSIC, BROWSE, LIBRARY, SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#323232")
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        setContent {
            EvilWizardRadioTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MusicScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .padding(16.dp)
            .padding(bottom = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                Text("Album Art", color = Color.White)
            }

            Spacer(modifier = Modifier.height(64.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Song Title", color = Color.White, fontSize = 18.sp)
                Text("Album Title", color = Color.Gray)
                Text("Artist Name", color = Color.Gray)
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    "Eq / Q / /* / ...",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.CenterStart)

                )

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔀")
                    Spacer(Modifier.width(12.dp))
                    Text("🔁")
                }
            }

                Spacer(modifier = Modifier.height(12.dp))


                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    // Left time
                    Text(
                        text = "0:42",
                        color = Color.White,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Progress bar (takes remaining space)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(Color.White)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Right time
                    Text(
                        text = "3:15",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                ) {
                    Text("⏮", fontSize = 28.sp, color = Color.White)

                    Spacer(modifier = Modifier.width(48.dp))
                    Text("⏯", fontSize = 32.sp, color = Color.White)

                    Spacer(modifier = Modifier.width(48.dp))
                    Text("⏭", fontSize = 28.sp, color = Color.White)

                }
            }
        }
    }



@Composable
fun BrowseScreen() {
    Text(
        text = "Browse Screen",
        color = Color.White

    )
}

@Composable
fun LibraryScreen() {
    Text(
        text = "Library Screen",
        color = Color.White)
}

@Composable
fun SettingsScreen() {
    Text(
        text = "Settings Screen",
        color = Color.White)
}




@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(Tab.MUSIC) }

    Column (
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
    ){
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                Tab.MUSIC -> MusicScreen()
                Tab.BROWSE -> BrowseScreen()
                Tab.LIBRARY -> LibraryScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }

        BottomBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
        )
    }
}

@Composable
fun BottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF323232))
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            BottomTabItem(Tab.MUSIC, "Music", selectedTab, onTabSelected)
            BottomTabItem(Tab.BROWSE, "Browse", selectedTab, onTabSelected)
            BottomTabItem(Tab.LIBRARY, "Library", selectedTab, onTabSelected)
            BottomTabItem(Tab.SETTINGS, "Settings", selectedTab, onTabSelected)
        }
    }
}

@Composable
fun RowScope.BottomTabItem(
    tab: Tab,
    label: String,
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit
) {
    val selected = tab == selectedTab

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(
                Color(0xFF323232)
            )
            .clickable { onTabSelected(tab) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White
        )
    }
}

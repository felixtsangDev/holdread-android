package app.holdread

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.holdread.reader.ReaderScreen
import app.holdread.reader.SampleBook
import app.holdread.reader.rememberReaderState
import app.holdread.ui.HoldReadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HoldReadTheme {
                ReaderScreen(rememberReaderState(SampleBook.book))
            }
        }
    }
}

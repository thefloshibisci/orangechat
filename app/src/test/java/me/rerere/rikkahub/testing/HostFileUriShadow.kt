package me.rerere.rikkahub.testing

import android.net.Uri
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/** Android's Uri.fromFile assumes Unix paths; Robolectric files live on the host OS. */
@Implements(Uri::class)
class HostFileUriShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun fromFile(file: File): Uri = Uri.parse("file://" + file.toURI().rawPath)
    }
}

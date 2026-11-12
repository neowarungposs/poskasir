package com.neowarungposs.poskasir

import android.content.Context
import com.neowarungposs.poskasir.core.BrandingStore
import com.neowarungposs.poskasir.core.SecureStore
import com.neowarungposs.poskasir.core.SupabaseClient

/** Simple service locator: one instance of each core service per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val secureStore = SecureStore(appContext)
    val supabase = SupabaseClient(secureStore)
    val branding = BrandingStore(appContext)
    val session get() = supabase.session
}

package me.rerere.rikkahub.data.db

import android.content.Context
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import me.rerere.rikkahub.data.db.migrations.*

/** Shared by the live database and the isolated backup preflight. */
internal val APP_DATABASE_MIGRATIONS = arrayOf(
    Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16,
    Migration_19_20, Migration_20_21, Migration_21_22, Migration_23_24, Migration_24_25,
    Migration_25_26, Migration_30_32, Migration_31_32, Migration_32_33, Migration_33_34,
    Migration_34_35, Migration_35_36, Migration_36_37, Migration_37_38, Migration_38_39, Migration_39_40, Migration_40_41, Migration_41_42, Migration_42_43,
)

internal fun appSQLiteOpenHelperFactory(context: Context) = RequerySQLiteOpenHelperFactory(
    listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
        options.customExtensions.add(SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null))
        options
    })
)

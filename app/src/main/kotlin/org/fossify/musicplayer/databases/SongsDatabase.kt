package org.fossify.musicplayer.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fossify.musicplayer.interfaces.*
import org.fossify.musicplayer.models.*
import org.fossify.musicplayer.objects.MyExecutor
import org.fossify.musicplayer.playback.queue.PlaybackCursorRow
import org.fossify.musicplayer.playback.queue.QueueDao
import org.fossify.musicplayer.playback.queue.QueueEntryRow
import org.fossify.musicplayer.playback.queue.QueueGroupRow

@Database(
    entities = [
        Track::class, Playlist::class, Artist::class, Album::class, Genre::class,
        QueueEntryRow::class, QueueGroupRow::class, PlaybackCursorRow::class,
    ],
    version = 16
)
abstract class SongsDatabase : RoomDatabase() {

    abstract fun SongsDao(): SongsDao

    abstract fun PlaylistsDao(): PlaylistsDao

    abstract fun QueueDao(): QueueDao

    abstract fun ArtistsDao(): ArtistsDao

    abstract fun AlbumsDao(): AlbumsDao

    abstract fun GenresDao(): GenresDao

    companion object {
        private var db: SongsDatabase? = null

        fun getInstance(context: Context): SongsDatabase {
            if (db == null) {
                synchronized(SongsDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, SongsDatabase::class.java, "songs.db")
                            .setQueryExecutor(MyExecutor.myExecutor)
                            .addMigrations(MIGRATION_1_2)
                            .addMigrations(MIGRATION_2_3)
                            .addMigrations(MIGRATION_3_4)
                            .addMigrations(MIGRATION_4_5)
                            .addMigrations(MIGRATION_5_6)
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .addMigrations(MIGRATION_10_11)
                            .addMigrations(MIGRATION_11_12)
                            .addMigrations(MIGRATION_12_13)
                            .addMigrations(MIGRATION_13_14)
                            .addMigrations(MIGRATION_14_15)
                            .addMigrations(MIGRATION_15_16)
                            .build()
                    }
                }
            }
            return db!!
        }

        fun destroyInstance() {
            db = null
        }

        // removing the "type" value of Song
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        "CREATE TABLE songs_new (media_store_id INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, path TEXT NOT NULL, duration INTEGER NOT NULL, " +
                            "album TEXT NOT NULL, playlist_id INTEGER NOT NULL, PRIMARY KEY(path, playlist_id))"
                    )

                    execSQL(
                        "INSERT INTO songs_new (media_store_id, title, artist, path, duration, album, playlist_id) " +
                            "SELECT media_store_id, title, artist, path, duration, album, playlist_id FROM songs"
                    )

                    execSQL("DROP TABLE songs")
                    execSQL("ALTER TABLE songs_new RENAME TO songs")

                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlists_id` ON `playlists` (`id`)")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN track_id INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE songs ADD COLUMN cover_art TEXT default '' NOT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE `queue_items` (`track_id` INTEGER NOT NULL PRIMARY KEY, `track_order` INTEGER NOT NULL, `is_current` INTEGER NOT NULL, `last_position` INTEGER NOT NULL)")
            }
        }

        // change the primary keys from path + playlist_id to media_store_id + playlist_id
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        "CREATE TABLE songs_new (media_store_id INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, path TEXT NOT NULL, duration INTEGER NOT NULL, " +
                            "album TEXT NOT NULL, cover_art TEXT default '' NOT NULL, playlist_id INTEGER NOT NULL, track_id INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(media_store_id, playlist_id))"
                    )

                    execSQL(
                        "INSERT OR IGNORE INTO songs_new (media_store_id, title, artist, path, duration, album, cover_art, playlist_id, track_id) " +
                            "SELECT media_store_id, title, artist, path, duration, album, cover_art, playlist_id, track_id FROM songs"
                    )

                    execSQL("DROP TABLE songs")
                    execSQL("ALTER TABLE songs_new RENAME TO tracks")
                }
            }
        }

        // adding an autoincrementing "id" field, replace primary keys with indices
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        "CREATE TABLE tracks_new (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `media_store_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `path` TEXT NOT NULL, `duration` INTEGER NOT NULL, " +
                            "`album` TEXT NOT NULL, `cover_art` TEXT default '' NOT NULL, `playlist_id` INTEGER NOT NULL, `track_id` INTEGER NOT NULL DEFAULT 0)"
                    )

                    execSQL(
                        "INSERT OR IGNORE INTO tracks_new (media_store_id, title, artist, path, duration, album, cover_art, playlist_id, track_id) " +
                            "SELECT media_store_id, title, artist, path, duration, album, cover_art, playlist_id, track_id FROM tracks"
                    )

                    execSQL("DROP TABLE tracks")
                    execSQL("ALTER TABLE tracks_new RENAME TO tracks")

                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_id` ON `tracks` (`media_store_id`, `playlist_id`)")
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("CREATE TABLE `artists` (`id` INTEGER NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `album_cnt` INTEGER NOT NULL, `track_cnt` INTEGER NOT NULL, `album_art_id` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_artists_id` ON `artists` (`id`)")

                    execSQL("CREATE TABLE `albums` (`id` INTEGER NOT NULL PRIMARY KEY, `artist` TEXT NOT NULL, `title` TEXT NOT NULL, `cover_art` TEXT NOT NULL, `year` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_albums_id` ON `albums` (`id`)")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tracks ADD COLUMN folder_name TEXT default '' NOT NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE albums ADD COLUMN track_cnt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE albums ADD COLUMN artist_id INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tracks ADD COLUMN album_id INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tracks ADD COLUMN order_in_playlist INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE tracks ADD COLUMN artist_id INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE tracks ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE tracks ADD COLUMN flags INTEGER NOT NULL DEFAULT 0")

                    execSQL("CREATE TABLE `artists_new` (`id` INTEGER NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `album_cnt` INTEGER NOT NULL, `track_cnt` INTEGER NOT NULL, `album_art` TEXT NOT NULL)")
                    execSQL("INSERT OR IGNORE INTO artists_new (id, title, album_cnt, track_cnt) SELECT id, title, album_cnt, track_cnt FROM artists")
                    execSQL("DROP TABLE artists")
                    execSQL("ALTER TABLE artists_new RENAME TO artists")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_artists_id` ON `artists` (`id`)")

                    database.execSQL("ALTER TABLE tracks ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE albums ADD COLUMN date_added INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
                    execSQL("ALTER TABLE tracks ADD COLUMN genre_id INTEGER NOT NULL DEFAULT 0")

                    execSQL("CREATE TABLE `genres` (`id` INTEGER NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `track_cnt` INTEGER NOT NULL, `album_art` TEXT NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_genres_id` ON `genres` (`id`)")
                }
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("ALTER TABLE tracks ADD COLUMN disc_number INTEGER DEFAULT NULL")
                }
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL(
                        "CREATE TABLE tracks_new (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `media_store_id` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `path` TEXT NOT NULL, `duration` INTEGER NOT NULL, " +
                            "`album` TEXT NOT NULL, genre TEXT NOT NULL DEFAULT '', `cover_art` TEXT default '' NOT NULL, `playlist_id` INTEGER NOT NULL, `track_id` INTEGER DEFAULT NULL, disc_number INTEGER DEFAULT NULL, " +
                            "folder_name TEXT default '' NOT NULL, album_id INTEGER NOT NULL DEFAULT 0, artist_id INTEGER NOT NULL DEFAULT 0, genre_id INTEGER NOT NULL DEFAULT 0, year INTEGER NOT NULL DEFAULT 0, date_added INTEGER NOT NULL DEFAULT 0, " +
                            "order_in_playlist INTEGER NOT NULL DEFAULT 0, flags INTEGER NOT NULL DEFAULT 0)"
                    )
                    execSQL(
                        "INSERT INTO tracks_new(id,media_store_id,title,artist,path,duration,album,genre,cover_art,playlist_id,track_id,disc_number,folder_name,album_id,artist_id,genre_id,year,date_added,order_in_playlist,flags) " +
                            "SELECT id,media_store_id,title,artist,path,duration,album,genre,cover_art,playlist_id,track_id,disc_number,folder_name,album_id,artist_id,genre_id,year,date_added,order_in_playlist,flags " +
                            "FROM tracks"
                    )
                    execSQL("DROP TABLE tracks")
                    execSQL("ALTER TABLE tracks_new RENAME to tracks")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_id` ON `tracks` (`media_store_id`, `playlist_id`)")
                }
            }
        }

        /**
         * Adds the logical queue tables and converts the old `queue_items` queue into them, so the queue
         * survives the update.
         *
         * The old queue is stored in unshuffled timeline order, so a shuffled order is not preserved.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `queue_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, `source` TEXT NOT NULL, `source_id` INTEGER)")
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `queue_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `position` REAL NOT NULL, `type` TEXT NOT NULL, " +
                            "`track_id` INTEGER, `completed_repetitions` INTEGER NOT NULL, `remaining_repetitions` INTEGER, `group_id` INTEGER, " +
                            "FOREIGN KEY(`group_id`) REFERENCES `queue_groups`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                    )
                    execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_position` ON `queue_entries` (`position`)")
                    execSQL("CREATE INDEX IF NOT EXISTS `index_queue_entries_group_id` ON `queue_entries` (`group_id`)")
                    execSQL("CREATE TABLE IF NOT EXISTS `playback_cursor` (`id` INTEGER NOT NULL, `head_position` REAL NOT NULL, `is_active` INTEGER NOT NULL, `position_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))")

                    // each old row becomes a track entry that plays once
                    execSQL(
                        "INSERT INTO queue_entries (position, type, track_id, completed_repetitions, remaining_repetitions) " +
                            "SELECT CAST(track_order AS REAL), 'TRACK', track_id, 0, 1 FROM queue_items ORDER BY track_order"
                    )

                    // the old current track becomes the head, everything before it becomes history
                    execSQL(
                        "INSERT INTO playback_cursor (id, head_position, is_active, position_ms) VALUES (0, " +
                            "COALESCE((SELECT CAST(track_order AS REAL) FROM queue_items WHERE is_current = 1 LIMIT 1), 0.0), " +
                            "COALESCE((SELECT last_position > 0 FROM queue_items WHERE is_current = 1 LIMIT 1), 0), " +
                            "COALESCE((SELECT last_position * 1000 FROM queue_items WHERE is_current = 1 LIMIT 1), 0))"
                    )

                    execSQL("DROP TABLE queue_items")
                }
            }
        }
    }
}

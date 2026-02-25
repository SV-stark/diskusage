/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.google.android.diskusage.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.diskusage.R
import com.google.android.diskusage.filesystem.entity.FileSystemEntry
import timber.log.Timber
import java.io.File
import kotlin.io.FileTreeWalk
import kotlin.io.FileWalkDirection

class DeleteActivity : ComponentActivity() {

    private var responseIntent: Intent? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //    Debug.startMethodTracing("diskusage");
        FileSystemEntry.setupStrings(this)
        val path = intent.getStringExtra(DiskUsage.DELETE_PATH_KEY)
        val absolutePath = intent.getStringExtra(DiskUsage.DELETE_ABSOLUTE_PATH_KEY)
        Timber.d("onCreate: %s -> %s", path, absolutePath)

        val sizeString = intent.getStringExtra(SIZE_KEY)
        val count = intent.getIntExtra(NUM_FILES_KEY, 0)
        
        val fileInfos = mutableListOf<FileInfo>()
        if (absolutePath != null) {
            val file = File(absolutePath)
            if (file.exists()) {
                val fileTreeWalk = FileTreeWalk(file, FileWalkDirection.TOP_DOWN)
                for (sub in fileTreeWalk) {
                    if (sub.isFile) {
                        val size = FileSystemEntry.calcSizeString(sub.length())
                        fileInfos.add(FileInfo(size, sub.name))
                    } else if (sub.isDirectory) {
                        fileInfos.add(FileInfo("", sub.name))
                    }
                }
            }
        }

        responseIntent = Intent()
        responseIntent?.putExtra(DiskUsage.DELETE_PATH_KEY, path)

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = stringResource(R.string.delete_title)) }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = getString(R.string.delete_summary, count, sizeString),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(fileInfos) { info ->
                                Text(text = "${info.size} - ${info.name}")
                            }
                        }

                        Button(
                            onClick = {
                                setResult(DiskUsage.RESULT_DELETE_CONFIRMED, responseIntent)
                                finish()
                            },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                        
                        Button(
                            onClick = {
                                setResult(DiskUsage.RESULT_DELETE_CANCELED)
                                finish()
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }

                    }
                }
            }
        }
    }

    companion object {
        const val NUM_FILES_KEY = "numFiles"
        const val SIZE_KEY = "size"
    }
}

data class FileInfo(val size: String, val name: String)

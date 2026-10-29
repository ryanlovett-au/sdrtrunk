/*
 * *****************************************************************************
 * Copyright (C) 2026 Ryan Lovett
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.configuration.transfer;

import io.github.dsheirer.console.ConsoleLayoutManager;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Writes playlists (and their console layouts) from a configuration bundle into a playlist directory.  Existing files
 * with the same name are backed up (renamed with a .pre-import-[timestamp] suffix) before they are replaced.
 */
public final class PlaylistImporter
{
    /**
     * Backup file suffix: [file name].pre-import-[yyyyMMdd_HHmmss]
     */
    public static final String BACKUP_SUFFIX = ".pre-import-";
    public static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Lock file suffix used by the playlist manager while saving a playlist (see PlaylistPreference.getPlaylistLock())
     */
    private static final String PLAYLIST_LOCK_SUFFIX = ".lck";
    private static final String TEMPORARY_FILE_SUFFIX = ".tmp";

    private PlaylistImporter()
    {
    }

    /**
     * Creates a backup timestamp for the current time.
     */
    public static String createTimestamp()
    {
        return LocalDateTime.now().format(BACKUP_TIMESTAMP);
    }

    /**
     * Target file path for the playlist in the directory.
     * @param directory for playlists
     * @param name of the playlist file (validated bundle name)
     * @return target path
     * @throws IOException if the name is not a safe file name or resolves outside the directory
     */
    public static Path getTargetPath(Path directory, String name) throws IOException
    {
        if(!ConfigurationBundle.isValidPlaylistName(name))
        {
            throw new ConfigurationBundleException("Invalid playlist name [" + name + "]");
        }

        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path target = normalizedDirectory.resolve(name).normalize();

        if(!normalizedDirectory.equals(target.getParent()))
        {
            throw new ConfigurationBundleException("Invalid playlist name [" + name + "]");
        }

        return target;
    }

    /**
     * Names of the playlists that would replace an existing playlist or console layout file in the directory.
     * @param names of the playlists to import
     * @param directory for the imported playlists
     * @return names of the playlists that exist in the directory
     */
    public static List<String> getExistingPlaylists(Collection<String> names, Path directory)
    {
        List<String> existing = new ArrayList<>();

        for(String name: names)
        {
            try
            {
                Path target = getTargetPath(directory, name);

                if(Files.exists(target) || Files.exists(ConsoleLayoutManager.getSidecarPath(target)))
                {
                    existing.add(name);
                }
            }
            catch(IOException ioe)
            {
                //Invalid names are rejected when the bundle is read
            }
        }

        return existing;
    }

    /**
     * Writes the playlists (and console layouts) into the directory.  An existing playlist file and an existing console
     * layout file are backed up before they are replaced.  When the bundle has no console layout for a playlist, an
     * existing console layout file is backed up and removed, so that the imported playlist starts with an empty
     * console layout as it had on the other computer.
     *
     * @param bundle containing the playlists
     * @param names of the playlists to import
     * @param directory for the playlists
     * @param timestamp for backup file names (see createTimestamp())
     * @return written files and backups
     * @throws IOException if a file can't be written
     */
    public static Result writePlaylists(ConfigurationBundle bundle, Collection<String> names, Path directory,
                                        String timestamp) throws IOException
    {
        List<Path> written = new ArrayList<>();
        List<Path> backups = new ArrayList<>();

        Files.createDirectories(directory);

        for(String name: names)
        {
            byte[] playlist = bundle.getPlaylist(name);

            if(playlist == null)
            {
                throw new ConfigurationBundleException("The configuration file does not contain playlist [" + name + "]");
            }

            Path target = getTargetPath(directory, name);
            Path layoutTarget = ConsoleLayoutManager.getSidecarPath(target);
            byte[] layout = bundle.getConsoleLayout(name);

            if(Files.exists(target))
            {
                backups.add(backup(target, timestamp, false));
            }

            if(Files.exists(layoutTarget))
            {
                //Move the existing console layout aside when the bundle has no console layout for this playlist
                backups.add(backup(layoutTarget, timestamp, layout == null));
            }

            //A stale playlist lock file causes the playlist manager to restore the playlist backup file over the
            //imported playlist when it loads the playlist
            Files.deleteIfExists(Path.of(target + PLAYLIST_LOCK_SUFFIX));

            write(target, playlist);

            if(layout != null)
            {
                write(layoutTarget, layout);
            }

            written.add(target);
        }

        return new Result(Collections.unmodifiableList(written), Collections.unmodifiableList(backups));
    }

    /**
     * Creates a backup of the file: [file name].pre-import-[timestamp] (with a -2, -3, ... suffix when the backup file
     * already exists).
     * @param path to backup
     * @param timestamp for the backup file name
     * @param move to move (rename) the file instead of copying it
     * @return backup file path
     */
    private static Path backup(Path path, String timestamp, boolean move) throws IOException
    {
        String base = path.getFileName().toString() + BACKUP_SUFFIX + timestamp;
        Path backup = path.resolveSibling(base);

        for(int x = 2; Files.exists(backup); x++)
        {
            backup = path.resolveSibling(base + "-" + x);
        }

        if(move)
        {
            Files.move(path, backup);
        }
        else
        {
            Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }

        return backup;
    }

    /**
     * Writes the file atomically: writes a temporary file and moves it over the file.
     */
    private static void write(Path path, byte[] content) throws IOException
    {
        Path temporary = path.resolveSibling(path.getFileName().toString() + TEMPORARY_FILE_SUFFIX);

        try
        {
            Files.write(temporary, content);

            try
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch(AtomicMoveNotSupportedException amnse)
            {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Playlist import result
     * @param written playlist files that were written
     * @param backups backup files of the replaced playlist and console layout files
     */
    public record Result(List<Path> written, List<Path> backups)
    {
    }
}

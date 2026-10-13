/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the console layout (groups of alias-bound cards and console settings) and persists the layout as JSON.
 *
 * Each playlist has its own console layout, stored in a sidecar file next to the playlist file (see getSidecarPath()).
 * A single manager instance is used for the application lifetime: when a playlist is loaded or switched, load(Path)
 * saves any pending changes for the previous layout file, loads the layout for the new playlist and broadcasts a
 * LAYOUT_LOADED event, so listeners can hold a reference to this manager across playlist changes.
 *
 * Cards reference aliases by alias unique identifier (UUID) and also store a fallback alias signature.  When an alias
 * model is registered, the layout is bound against the alias model each time it is loaded: card signatures are
 * refreshed from the bound aliases, and a card whose alias UUID is not found is re-bound to the single alias that
 * matches its signature (if any) or is otherwise kept unchanged as an unlinked card (see isCardLinked()).
 *
 * All modifications are made via this manager so that the following constraints are enforced:
 * - An alias may be bound to at most one card across all groups.
 * - Card volume is constrained to the range 0.0 - 1.0 (linear gain).
 *
 * Accessor methods return snapshots (copies) of the layout, groups and cards, so modifying a returned object does not
 * change the managed layout.  Each modification broadcasts a ConsoleLayoutEvent to registered listeners (on the
 * calling thread, after the change is applied) and schedules a (debounced) save of the layout file.
 *
 * The layout file is saved atomically (written to a temporary file and then moved over the layout file).  A missing
 * layout file results in an empty layout.  A corrupt (unreadable) layout file is renamed with a '.corrupt' suffix and
 * timestamp so that it can be inspected, and the manager starts with an empty layout.
 */
public class ConsoleLayoutManager
{
    private final static Logger mLog = LoggerFactory.getLogger(ConsoleLayoutManager.class);
    /**
     * Legacy (global) console layout directory and file name, used before console layouts were stored per playlist.
     */
    public static final String CONSOLE_DIRECTORY = "console";
    public static final String LAYOUT_FILE_NAME = "console_layout.json";
    public static final String SIDECAR_FILE_SUFFIX = ".console.json";
    public static final String MIGRATED_FILE_SUFFIX = ".migrated";
    public static final String DEFAULT_GROUP_NAME = "Group";
    private static final String TEMPORARY_FILE_SUFFIX = ".tmp";
    private static final String CORRUPT_FILE_SUFFIX = ".corrupt.";
    private static final long SAVE_DELAY_MS = 2000;
    private static final DateTimeFormatter CORRUPT_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper mObjectMapper;
    private final Broadcaster<ConsoleLayoutEvent> mBroadcaster = new Broadcaster<>();
    private final Object mSaveLock = new Object();
    private final Object mSaveFutureLock = new Object();
    private final AtomicBoolean mSavePending = new AtomicBoolean();
    private ScheduledFuture<?> mSaveFuture;
    private ConsoleLayout mLayout = new ConsoleLayout();
    private Path mLayoutPath;
    private volatile AliasModel mAliasModel;

    /**
     * Constructs an instance that has an empty layout and no layout file.  Invoke load(Path) to load the layout for a
     * playlist.
     */
    public ConsoleLayoutManager()
    {
        this(null);
    }

    /**
     * Constructs an instance that uses the specified layout file.  Invoke load() to load the layout from disk.
     *
     * @param layoutPath for the layout file
     */
    public ConsoleLayoutManager(Path layoutPath)
    {
        mLayoutPath = layoutPath;
        mObjectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Legacy (global) console layout file path used before console layouts were stored per playlist:
     * [application root]/console/console_layout.json
     * @param userPreferences for resolving the application root directory
     * @return legacy layout file path
     */
    public static Path getLegacyLayoutPath(UserPreferences userPreferences)
    {
        return userPreferences.getDirectoryPreference().getDirectoryApplicationRoot().resolve(CONSOLE_DIRECTORY)
            .resolve(LAYOUT_FILE_NAME);
    }

    /**
     * Console layout (sidecar) file path for the playlist: the playlist file name with the file extension replaced by
     * '.console.json', in the same directory as the playlist.  For example, playlist /path/foo.xml uses console layout
     * file /path/foo.console.json.  A playlist file name without an extension has the suffix appended.
     *
     * @param playlistPath for the playlist file
     * @return console layout file path or null if the playlist path is null.
     */
    public static Path getSidecarPath(Path playlistPath)
    {
        if(playlistPath == null || playlistPath.getFileName() == null)
        {
            return null;
        }

        String name = playlistPath.getFileName().toString();
        int extension = name.lastIndexOf('.');

        if(extension > 0)
        {
            name = name.substring(0, extension);
        }

        return playlistPath.resolveSibling(name + SIDECAR_FILE_SUFFIX);
    }

    /**
     * One-time migration of the legacy (global) console layout file.  When the legacy file exists, it is copied to the
     * layout file (adopted) if the layout file does not already exist, and the legacy file is then renamed with a
     * '.migrated' suffix so that the migration is not repeated.
     *
     * @param legacyPath for the legacy console layout file
     * @param layoutPath for the console layout file of the current playlist
     * @return true if the legacy file was adopted as the layout file.
     */
    public static boolean migrateLegacyLayout(Path legacyPath, Path layoutPath)
    {
        if(legacyPath == null || layoutPath == null || !Files.exists(legacyPath))
        {
            return false;
        }

        boolean adopted = false;

        try
        {
            if(Files.exists(layoutPath))
            {
                mLog.info("Legacy console layout [" + legacyPath + "] was not adopted - console layout [" + layoutPath +
                    "] already exists");
            }
            else
            {
                if(layoutPath.getParent() != null)
                {
                    Files.createDirectories(layoutPath.getParent());
                }

                Files.copy(legacyPath, layoutPath);
                adopted = true;
                mLog.info("Legacy console layout [" + legacyPath + "] was adopted as console layout [" + layoutPath + "]");
            }

            Path migrated = legacyPath.resolveSibling(legacyPath.getFileName().toString() + MIGRATED_FILE_SUFFIX);
            Files.move(legacyPath, migrated, StandardCopyOption.REPLACE_EXISTING);
            mLog.info("Legacy console layout file was renamed to [" + migrated + "]");
        }
        catch(Exception e)
        {
            mLog.error("Error migrating legacy console layout [" + legacyPath + "] to [" + layoutPath + "]", e);
        }

        return adopted;
    }

    /**
     * Current console layout file path, or null if a layout file has not been loaded.
     */
    public synchronized Path getLayoutPath()
    {
        return mLayoutPath;
    }

    /**
     * Registers the alias model used to capture card alias signatures and to bind (and re-bind) cards to aliases
     * when the layout is loaded.  Invoke before loading the layout.
     * @param aliasModel containing the aliases for the current playlist, or null.
     */
    public void setAliasModel(AliasModel aliasModel)
    {
        mAliasModel = aliasModel;
    }

    /**
     * Registers the listener to receive console layout change events.
     */
    public void addListener(Listener<ConsoleLayoutEvent> listener)
    {
        mBroadcaster.addListener(listener);
    }

    /**
     * Deregisters the listener from receiving console layout change events.
     */
    public void removeListener(Listener<ConsoleLayoutEvent> listener)
    {
        mBroadcaster.removeListener(listener);
    }

    /**
     * Broadcasts the event to registered listeners.  This is invoked outside of the synchronization lock so that
     * listeners can safely access this manager.
     */
    private void broadcast(ConsoleLayoutEvent event)
    {
        mBroadcaster.broadcast(event);
    }

    /**
     * Loads the console layout from the current layout file, replacing the current layout.  A missing file results in
     * an empty layout.  A corrupt file is renamed (kept as a backup) and results in an empty layout.
     */
    public void load()
    {
        load(getLayoutPath(), false);
    }

    /**
     * Loads the console layout from the specified layout file and makes it the current layout file, for example when
     * a playlist is loaded or switched.  Any pending (unsaved) changes to the current layout are first saved to the
     * current layout file.  Broadcasts a LAYOUT_LOADED event.
     *
     * @param layoutPath for the layout file to load
     */
    public void load(Path layoutPath)
    {
        load(layoutPath, true);
    }

    /**
     * Loads the console layout from the layout file, replacing the current layout and layout file.
     * @param path for the layout file
     * @param saveCurrent to save any pending changes for the current layout before loading
     */
    private void load(Path path, boolean saveCurrent)
    {
        if(saveCurrent)
        {
            saveNow();
        }

        ConsoleLayout layout = null;
        boolean modified = false;

        if(path != null && Files.exists(path))
        {
            try
            {
                layout = mObjectMapper.readValue(path.toFile(), ConsoleLayout.class);

                if(layout == null)
                {
                    throw new IOException("Console layout file has no content");
                }

                mLog.info("Loaded console layout [" + path + "] with [" + layout.getGroups().size() + "] groups and [" +
                    layout.getCardCount() + "] cards");
            }
            catch(Exception e)
            {
                mLog.error("Unable to read console layout file [" + path + "] - starting with an empty console layout", e);
                backupCorruptFile(path);
                layout = null;
            }
        }
        else
        {
            mLog.info("Console layout file [" + path + "] not found - starting with an empty console layout");
        }

        if(layout == null)
        {
            layout = new ConsoleLayout();
        }
        else
        {
            modified = validate(layout);

            if(bindAliases(layout, mAliasModel, true).modified())
            {
                modified = true;
            }
        }

        synchronized(this)
        {
            mLayoutPath = path;
            mLayout = layout;
        }

        if(modified)
        {
            mLog.info("Console layout was corrected during load - scheduling save");
            scheduleSave();
        }

        broadcast(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.LAYOUT_LOADED, null, null));
    }

    /**
     * Renames the corrupt layout file with a corrupt suffix and timestamp so that it is retained for inspection and
     * will not be loaded again.
     * @param path of the corrupt file
     */
    private void backupCorruptFile(Path path)
    {
        Path backup = path.resolveSibling(path.getFileName().toString() + CORRUPT_FILE_SUFFIX +
            LocalDateTime.now().format(CORRUPT_FILE_TIMESTAMP));

        try
        {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            mLog.info("Corrupt console layout file was moved to [" + backup + "]");
        }
        catch(Exception e)
        {
            mLog.error("Unable to move corrupt console layout file [" + path + "] to backup file [" + backup + "]", e);
        }
    }

    /**
     * Validates and corrects the layout after loading: assigns missing or duplicate identifiers, removes cards that
     * have no alias or that bind an alias already bound to a previous card, constrains values to valid ranges, and
     * sorts and renumbers the groups by display order.
     *
     * @param layout to validate
     * @return true if the layout was modified.
     */
    static boolean validate(ConsoleLayout layout)
    {
        boolean modified = false;

        if(layout.getActivityHangTimeMs() < 0)
        {
            layout.setActivityHangTimeMs(ConsoleLayout.DEFAULT_ACTIVITY_HANG_TIME_MS);
            modified = true;
        }

        if(layout.getVersion() != ConsoleLayout.CURRENT_VERSION)
        {
            layout.setVersion(ConsoleLayout.CURRENT_VERSION);
            modified = true;
        }

        if(layout.getGroups().removeIf(group -> group == null))
        {
            modified = true;
        }

        Set<String> groupIds = new HashSet<>();
        Set<String> cardIds = new HashSet<>();
        Set<String> aliasUuids = new HashSet<>();

        for(ConsoleGroup group: layout.getGroups())
        {
            if(group.getId() == null || group.getId().isBlank() || groupIds.contains(group.getId()))
            {
                group.setId(UUID.randomUUID().toString());
                modified = true;
            }

            groupIds.add(group.getId());

            if(group.getName() == null)
            {
                group.setName(DEFAULT_GROUP_NAME);
                modified = true;
            }

            Iterator<ConsoleCard> it = group.getCards().iterator();

            while(it.hasNext())
            {
                ConsoleCard card = it.next();

                if(card == null || card.getAliasUuid() == null || card.getAliasUuid().isBlank())
                {
                    mLog.warn("Removing console card that is not bound to an alias");
                    it.remove();
                    modified = true;
                }
                else if(aliasUuids.contains(card.getAliasUuid()))
                {
                    mLog.warn("Removing console card [" + card.getId() + "] - alias [" + card.getAliasUuid() +
                        "] is already bound to another console card");
                    it.remove();
                    modified = true;
                }
                else
                {
                    aliasUuids.add(card.getAliasUuid());

                    if(card.getId() == null || card.getId().isBlank() || cardIds.contains(card.getId()))
                    {
                        card.setId(UUID.randomUUID().toString());
                        modified = true;
                    }

                    cardIds.add(card.getId());
                }
            }
        }

        //Stable sort of the groups by display order and then renumber
        List<ConsoleGroup> groups = new ArrayList<>(layout.getGroups());
        groups.sort(Comparator.comparingInt(ConsoleGroup::getDisplayOrder));
        layout.setGroups(groups);

        if(renumberGroups(layout))
        {
            modified = true;
        }

        return modified;
    }

    /**
     * Updates the display order of each group to match the group's index in the list of groups.
     * @param layout to renumber
     * @return true if any group display order changed.
     */
    private static boolean renumberGroups(ConsoleLayout layout)
    {
        boolean modified = false;

        for(int x = 0; x < layout.getGroups().size(); x++)
        {
            ConsoleGroup group = layout.getGroups().get(x);

            if(group.getDisplayOrder() != x)
            {
                group.setDisplayOrder(x);
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Binds the layout's cards against the aliases in the alias model.
     *
     * For each card whose alias unique identifier matches an alias in the model, the card's alias signature is
     * refreshed from the alias.  For each card whose alias unique identifier is not found (unlinked), the card is
     * re-bound to the single alias that matches the card's signature, preferring (in order) a match on alias list, alias
     * name and talkgroup, then alias list and alias name, then alias list and talkgroup.  A card is never re-bound to
     * an alias that is already bound to a card.  When no single matching alias is found, the card is kept unchanged.
     *
     * Note: this method is not thread safe - the layout must not be accessed concurrently.
     *
     * @param layout to bind
     * @param aliasModel containing the aliases, or null to skip binding
     * @param logUnlinked to log (at info level) each card that remains unlinked
     * @return binding result
     */
    static BindingResult bindAliases(ConsoleLayout layout, AliasModel aliasModel, boolean logUnlinked)
    {
        if(aliasModel == null)
        {
            return new BindingResult(false, false);
        }

        boolean modified = false;
        boolean rebound = false;
        Set<String> boundAliasUuids = new HashSet<>();
        List<ConsoleCard> unlinkedCards = new ArrayList<>();

        for(ConsoleGroup group: layout.getGroups())
        {
            for(ConsoleCard card: group.getCards())
            {
                Alias alias = aliasModel.getAliasByUuid(card.getAliasUuid());

                if(alias != null)
                {
                    boundAliasUuids.add(alias.getUuid());
                    AliasSignature signature = AliasSignature.create(alias);

                    if(!signature.equals(card.getSignature()))
                    {
                        card.setSignature(signature);
                        modified = true;
                    }
                }
                else
                {
                    unlinkedCards.add(card);
                }
            }
        }

        if(!unlinkedCards.isEmpty())
        {
            List<Alias> aliases = new ArrayList<>(aliasModel.getAliases());

            for(ConsoleCard card: unlinkedCards)
            {
                Alias alias = findAlias(card.getSignature(), aliases, boundAliasUuids);

                if(alias != null)
                {
                    mLog.info("Console card [" + card.getId() + "] alias [" + card.getAliasUuid() +
                        "] was not found - re-binding the card by signature to alias [" + alias.getUuid() + "] " +
                        card.getSignature());
                    card.setAliasUuid(alias.getUuid());
                    card.setSignature(AliasSignature.create(alias));
                    boundAliasUuids.add(alias.getUuid());
                    modified = true;
                    rebound = true;
                }
                else if(logUnlinked)
                {
                    mLog.info("Console card [" + card.getId() + "] alias [" + card.getAliasUuid() + "] was not found " +
                        "and no single alias matches the card signature " + card.getSignature() +
                        " - the card is unlinked");
                }
            }
        }

        return new BindingResult(modified, rebound);
    }

    /**
     * Finds the single alias that matches the signature and that is not already bound to a card.  Signature match
     * criteria are evaluated in order of preference: alias list, alias name and talkgroup, then alias list and alias
     * name, then alias list and talkgroup.  Evaluation stops at the first criteria that matches one or more aliases.
     *
     * @param signature to match
     * @param aliases to search
     * @param boundAliasUuids for aliases that are already bound to a card
     * @return matching alias or null if no alias, or more than one alias, matches.
     */
    private static Alias findAlias(AliasSignature signature, List<Alias> aliases, Set<String> boundAliasUuids)
    {
        if(signature == null || signature.getAliasListName() == null)
        {
            return null;
        }

        List<Predicate<Alias>> criteria = new ArrayList<>();

        if(signature.getAliasName() != null && signature.hasTalkgroup())
        {
            criteria.add(alias -> signature.matchesName(alias) && signature.matchesTalkgroup(alias));
        }

        if(signature.getAliasName() != null)
        {
            criteria.add(signature::matchesName);
        }

        if(signature.hasTalkgroup())
        {
            criteria.add(signature::matchesTalkgroup);
        }

        for(Predicate<Alias> criterion: criteria)
        {
            List<Alias> matches = new ArrayList<>();

            for(Alias alias: aliases)
            {
                if(alias != null && !boundAliasUuids.contains(alias.getUuid()) && signature.matchesList(alias) &&
                    criterion.test(alias))
                {
                    matches.add(alias);
                }
            }

            if(matches.size() == 1)
            {
                return matches.getFirst();
            }
            else if(matches.size() > 1)
            {
                mLog.info("Unable to re-bind console card by signature " + signature + " - [" + matches.size() +
                    "] aliases match the signature");
                return null;
            }
        }

        return null;
    }

    /**
     * Binds the current layout against the registered alias model: refreshes card alias signatures and re-binds
     * unlinked cards by signature.  Invoke when aliases are added to the alias model after the layout was loaded
     * (e.g. alias import).  Schedules a save when the layout changes and broadcasts a LAYOUT_LOADED event when any card
     * is re-bound to a different alias.
     */
    public void refreshAliasBindings()
    {
        BindingResult result;

        synchronized(this)
        {
            result = bindAliases(mLayout, mAliasModel, false);
        }

        if(result.modified())
        {
            scheduleSave();
        }

        if(result.rebound())
        {
            broadcast(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.LAYOUT_LOADED, null, null));
        }
    }

    /**
     * Indicates if the card is linked: the card's alias unique identifier matches an alias in the registered alias
     * model.  An unlinked card is kept in the layout, but does not match any calls until it is re-bound.
     *
     * @param cardId to check
     * @return true if the card exists and is bound to an alias in the alias model.
     */
    public boolean isCardLinked(String cardId)
    {
        String aliasUuid;

        synchronized(this)
        {
            ConsoleCard card = mLayout.getCard(cardId);

            if(card == null)
            {
                return false;
            }

            aliasUuid = card.getAliasUuid();
        }

        AliasModel aliasModel = mAliasModel;
        return aliasModel != null && aliasModel.getAliasByUuid(aliasUuid) != null;
    }

    /**
     * Result of binding a layout against an alias model.
     * @param modified indicates if any card (alias or signature) was modified
     * @param rebound indicates if any card was re-bound to a different alias
     */
    record BindingResult(boolean modified, boolean rebound)
    {
    }

    /**
     * Schedules a debounced save of the layout file.  Subsequent requests are ignored until the scheduled save runs.
     */
    private void scheduleSave()
    {
        if(mSavePending.compareAndSet(false, true))
        {
            synchronized(mSaveFutureLock)
            {
                mSaveFuture = ThreadPool.SCHEDULED.schedule(new SaveTask(), SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Indicates if there are unsaved changes that are scheduled to be saved.
     */
    public boolean isSavePending()
    {
        return mSavePending.get();
    }

    /**
     * Saves any pending changes immediately, cancelling the scheduled save.  Invoke on application shutdown.
     */
    public void saveNow()
    {
        synchronized(mSaveFutureLock)
        {
            if(mSaveFuture != null)
            {
                mSaveFuture.cancel(false);
                mSaveFuture = null;
            }
        }

        if(mSavePending.getAndSet(false))
        {
            save();
        }
        else
        {
            //Wait for any in-progress scheduled save to complete
            synchronized(mSaveLock)
            {
                mLog.debug("Console layout save is complete");
            }
        }
    }

    /**
     * Saves any pending changes in preparation for application shutdown.
     */
    public void shutdown()
    {
        saveNow();
    }

    /**
     * Saves a snapshot of the current layout to the layout file atomically by writing a temporary file and then moving
     * the temporary file over the layout file.
     */
    private void save()
    {
        ConsoleLayout snapshot;
        Path path;

        //Capture the layout and layout file together so that a layout is never saved to another playlist's file
        synchronized(this)
        {
            snapshot = mLayout.copyOf();
            path = mLayoutPath;
        }

        if(path == null)
        {
            mLog.warn("Unable to save console layout - a console layout file has not been loaded");
            return;
        }

        synchronized(mSaveLock)
        {
            Path temporary = path.resolveSibling(path.getFileName().toString() + TEMPORARY_FILE_SUFFIX);

            try
            {
                if(path.getParent() != null)
                {
                    Files.createDirectories(path.getParent());
                }

                Files.write(temporary, mObjectMapper.writeValueAsBytes(snapshot));

                try
                {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                catch(AtomicMoveNotSupportedException amnse)
                {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch(Exception e)
            {
                mLog.error("Error saving console layout file [" + path + "]", e);

                try
                {
                    Files.deleteIfExists(temporary);
                }
                catch(IOException ioe)
                {
                    //Do nothing - we tried
                }
            }
        }
    }

    /**
     * Snapshot (deep copy) of the current console layout.
     */
    public synchronized ConsoleLayout getLayout()
    {
        return mLayout.copyOf();
    }

    /**
     * Snapshot of the group with the specified identifier.
     * @param groupId to find
     * @return copy of the group or null.
     */
    public synchronized ConsoleGroup getGroup(String groupId)
    {
        ConsoleGroup group = mLayout.getGroup(groupId);
        return group != null ? group.copyOf() : null;
    }

    /**
     * Snapshot of the card with the specified identifier.
     * @param cardId to find
     * @return copy of the card or null.
     */
    public synchronized ConsoleCard getCard(String cardId)
    {
        ConsoleCard card = mLayout.getCard(cardId);
        return card != null ? card.copyOf() : null;
    }

    /**
     * Snapshot of the card bound to the alias with the specified unique identifier.
     * @param aliasUuid to find
     * @return copy of the card or null.
     */
    public synchronized ConsoleCard getCardForAlias(String aliasUuid)
    {
        ConsoleCard card = mLayout.getCardForAlias(aliasUuid);
        return card != null ? card.copyOf() : null;
    }

    /**
     * Indicates if the alias is already bound to a card.
     * @param aliasUuid to check
     * @return true if a card is bound to the alias.
     */
    public synchronized boolean isAliasAssigned(String aliasUuid)
    {
        return mLayout.getCardForAlias(aliasUuid) != null;
    }

    /**
     * Activity hang time in milliseconds
     */
    public synchronized long getActivityHangTimeMs()
    {
        return mLayout.getActivityHangTimeMs();
    }

    /**
     * Sets the activity hang time
     * @param activityHangTimeMs in milliseconds, zero or greater
     */
    public void setActivityHangTimeMs(long activityHangTimeMs)
    {
        if(activityHangTimeMs < 0)
        {
            throw new IllegalArgumentException("Activity hang time must be zero or greater");
        }

        synchronized(this)
        {
            if(mLayout.getActivityHangTimeMs() == activityHangTimeMs)
            {
                return;
            }

            mLayout.setActivityHangTimeMs(activityHangTimeMs);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.SETTINGS_CHANGED, null, null));
    }

    /**
     * Indicates if console mode is enabled.
     */
    public synchronized boolean isConsoleModeEnabled()
    {
        return mLayout.isConsoleModeEnabled();
    }

    /**
     * Enables or disables console mode.
     */
    public void setConsoleModeEnabled(boolean enabled)
    {
        synchronized(this)
        {
            if(mLayout.isConsoleModeEnabled() == enabled)
            {
                return;
            }

            mLayout.setConsoleModeEnabled(enabled);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.SETTINGS_CHANGED, null, null));
    }

    /**
     * Indicates if the console talkgroup filter is enabled.
     */
    public synchronized boolean isTalkgroupFilterEnabled()
    {
        return mLayout.isTalkgroupFilterEnabled();
    }

    /**
     * Enables or disables the console talkgroup filter.
     */
    public void setTalkgroupFilterEnabled(boolean enabled)
    {
        synchronized(this)
        {
            if(mLayout.isTalkgroupFilterEnabled() == enabled)
            {
                return;
            }

            mLayout.setTalkgroupFilterEnabled(enabled);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.SETTINGS_CHANGED, null, null));
    }

    /**
     * Indicates if the activity log shows all traffic (true) or only calls on console cards (false).
     */
    public synchronized boolean isActivityLogShowAll()
    {
        return mLayout.isActivityLogShowAll();
    }

    /**
     * Sets the activity log mode.
     * @param showAll true to show all traffic, false to show only calls on console cards
     */
    public void setActivityLogShowAll(boolean showAll)
    {
        synchronized(this)
        {
            if(mLayout.isActivityLogShowAll() == showAll)
            {
                return;
            }

            mLayout.setActivityLogShowAll(showAll);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.SETTINGS_CHANGED, null, null));
    }

    /**
     * Indicates if the clear audio (encryption slip) alert is enabled.
     */
    public synchronized boolean isClearAudioAlertEnabled()
    {
        return mLayout.isClearAudioAlertEnabled();
    }

    /**
     * Enables or disables the clear audio (encryption slip) alert.
     */
    public void setClearAudioAlertEnabled(boolean enabled)
    {
        synchronized(this)
        {
            if(mLayout.isClearAudioAlertEnabled() == enabled)
            {
                return;
            }

            mLayout.setClearAudioAlertEnabled(enabled);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.SETTINGS_CHANGED, null, null));
    }

    /**
     * Adds a new (empty) group at the end of the group display order.
     * @param name of the group
     * @return snapshot of the new group
     */
    public ConsoleGroup addGroup(String name)
    {
        ConsoleGroup group = new ConsoleGroup(name != null ? name : DEFAULT_GROUP_NAME);

        synchronized(this)
        {
            group.setDisplayOrder(mLayout.getGroups().size());
            mLayout.getGroups().add(group);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.GROUP_ADDED, group.getId(), null));
        return group.copyOf();
    }

    /**
     * Removes the group and all of the group's cards.  A CARD_REMOVED event is broadcast for each card, followed by a
     * GROUP_REMOVED event.
     * @param groupId to remove
     * @return true if the group was removed.
     */
    public boolean removeGroup(String groupId)
    {
        List<ConsoleLayoutEvent> events = new ArrayList<>();

        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroup(groupId);

            if(group == null)
            {
                return false;
            }

            mLayout.getGroups().remove(group);
            renumberGroups(mLayout);

            for(ConsoleCard card: group.getCards())
            {
                events.add(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.CARD_REMOVED, groupId, card.copyOf()));
            }

            events.add(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.GROUP_REMOVED, groupId, null));
        }

        changed(events);
        return true;
    }

    /**
     * Renames the group.
     * @param groupId to rename
     * @param name to apply
     * @return true if the group was found.
     */
    public boolean renameGroup(String groupId, String name)
    {
        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroup(groupId);

            if(group == null)
            {
                return false;
            }

            group.setName(name != null ? name : DEFAULT_GROUP_NAME);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.GROUP_CHANGED, groupId, null));
        return true;
    }

    /**
     * Sets the group collapsed state (the console window shows a collapsed group's header only).  Broadcasts a
     * GROUP_STATE_CHANGED event when the state changes.
     * @param groupId to update
     * @param collapsed state
     * @return true if the group was found.
     */
    public boolean setGroupCollapsed(String groupId, boolean collapsed)
    {
        return updateGroup(groupId, group -> group.isCollapsed() != collapsed, group -> group.setCollapsed(collapsed));
    }

    /**
     * Sets the collapsed state of every group.  Broadcasts a GROUP_STATE_CHANGED event for each group that changes.
     * @param collapsed state
     */
    public void setAllGroupsCollapsed(boolean collapsed)
    {
        List<ConsoleLayoutEvent> events = new ArrayList<>();

        synchronized(this)
        {
            for(ConsoleGroup group: mLayout.getGroups())
            {
                if(group.isCollapsed() != collapsed)
                {
                    group.setCollapsed(collapsed);
                    events.add(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.GROUP_STATE_CHANGED, group.getId(),
                        null));
                }
            }
        }

        if(!events.isEmpty())
        {
            changed(events);
        }
    }

    /**
     * Sets the group muted state.  A muted group silences the audio of all of its cards without changing each card's
     * own mute setting (see ConsoleCardResolver.isAudioMuted()).  Broadcasts a GROUP_STATE_CHANGED event when the
     * state changes.
     * @param groupId to update
     * @param muted state
     * @return true if the group was found.
     */
    public boolean setGroupMuted(String groupId, boolean muted)
    {
        return updateGroup(groupId, group -> group.isMuted() != muted, group -> group.setMuted(muted));
    }

    /**
     * Applies the update to the group when the group is found and the update applies, and broadcasts a
     * GROUP_STATE_CHANGED event when the group was updated.
     * @param groupId to update
     * @param applies test of whether the update changes the group
     * @param update to apply
     * @return true if the group was found.
     */
    private boolean updateGroup(String groupId, Predicate<ConsoleGroup> applies, Consumer<ConsoleGroup> update)
    {
        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroup(groupId);

            if(group == null)
            {
                return false;
            }

            if(!applies.test(group))
            {
                return true;
            }

            update.accept(group);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.GROUP_STATE_CHANGED, groupId, null));
        return true;
    }

    /**
     * Moves the group to the specified display index, shifting the other groups.
     * @param groupId to move
     * @param index for the group, constrained to the valid range.
     * @return true if the group was found.
     */
    public boolean moveGroup(String groupId, int index)
    {
        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroup(groupId);

            if(group == null)
            {
                return false;
            }

            mLayout.getGroups().remove(group);
            mLayout.getGroups().add(Math.clamp(index, 0, mLayout.getGroups().size()), group);
            renumberGroups(mLayout);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.GROUPS_REORDERED, groupId, null));
        return true;
    }

    /**
     * Adds a new card bound to the alias at the end of the specified group, with default volume, mute and pan
     * settings.  An alias may be bound to at most one card across all groups.
     *
     * @param groupId of the group for the card
     * @param aliasUuid unique identifier of the alias to bind to the card
     * @return snapshot of the new card
     * @throws IllegalArgumentException if the alias unique identifier is null or empty or the group does not exist
     * @throws IllegalStateException if the alias is already bound to a card (see isAliasAssigned())
     */
    public ConsoleCard addCard(String groupId, String aliasUuid)
    {
        if(aliasUuid == null || aliasUuid.isBlank())
        {
            throw new IllegalArgumentException("Alias unique identifier is required");
        }

        ConsoleCard card = new ConsoleCard(aliasUuid);
        AliasModel aliasModel = mAliasModel;

        if(aliasModel != null)
        {
            card.setSignature(AliasSignature.create(aliasModel.getAliasByUuid(aliasUuid)));
        }

        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroup(groupId);

            if(group == null)
            {
                throw new IllegalArgumentException("Console group [" + groupId + "] does not exist");
            }

            if(mLayout.getCardForAlias(aliasUuid) != null)
            {
                throw new IllegalStateException("Alias [" + aliasUuid + "] is already assigned to a console card");
            }

            group.getCards().add(card);
        }

        changed(new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.CARD_ADDED, groupId, card.copyOf()));
        return card.copyOf();
    }

    /**
     * Removes the card.
     * @param cardId to remove
     * @return true if the card was removed.
     */
    public boolean removeCard(String cardId)
    {
        ConsoleLayoutEvent event;

        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroupForCard(cardId);

            if(group == null)
            {
                return false;
            }

            ConsoleCard card = group.getCard(cardId);
            group.getCards().remove(card);
            event = new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.CARD_REMOVED, group.getId(), card.copyOf());
        }

        changed(event);
        return true;
    }

    /**
     * Moves the card to the specified index within the target group, which can be the card's current group or a
     * different group.
     * @param cardId to move
     * @param targetGroupId for the card
     * @param index within the target group, constrained to the valid range.
     * @return true if the card and target group were found and the card was moved.
     */
    public boolean moveCard(String cardId, String targetGroupId, int index)
    {
        ConsoleLayoutEvent event;

        synchronized(this)
        {
            ConsoleGroup source = mLayout.getGroupForCard(cardId);
            ConsoleGroup target = mLayout.getGroup(targetGroupId);

            if(source == null || target == null)
            {
                return false;
            }

            ConsoleCard card = source.getCard(cardId);
            source.getCards().remove(card);
            target.getCards().add(Math.clamp(index, 0, target.getCards().size()), card);
            event = new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.CARD_MOVED, targetGroupId, card.copyOf());
        }

        changed(event);
        return true;
    }

    /**
     * Re-binds (re-links) the card to the specified alias, for example to re-link an unlinked card whose alias was
     * not found.  The card keeps its identifier, group position and audio settings, and its alias signature is
     * refreshed from the alias.  Broadcasts a CARD_REBOUND event.
     *
     * @param cardId of the card to re-bind
     * @param aliasUuid unique identifier of the alias to bind to the card
     * @return true if the card was found.
     * @throws IllegalArgumentException if the alias unique identifier is null or empty
     * @throws IllegalStateException if the alias is already bound to a different card
     */
    public boolean relinkCard(String cardId, String aliasUuid)
    {
        if(aliasUuid == null || aliasUuid.isBlank())
        {
            throw new IllegalArgumentException("Alias unique identifier is required");
        }

        AliasModel aliasModel = mAliasModel;
        AliasSignature signature = aliasModel != null ? AliasSignature.create(aliasModel.getAliasByUuid(aliasUuid)) : null;
        ConsoleLayoutEvent event;

        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroupForCard(cardId);

            if(group == null)
            {
                return false;
            }

            ConsoleCard card = group.getCard(cardId);
            ConsoleCard existing = mLayout.getCardForAlias(aliasUuid);

            if(existing != null && existing != card)
            {
                throw new IllegalStateException("Alias [" + aliasUuid + "] is already assigned to a console card");
            }

            if(aliasUuid.equals(card.getAliasUuid()) && (signature == null || signature.equals(card.getSignature())))
            {
                return true;
            }

            card.setAliasUuid(aliasUuid);

            if(signature != null)
            {
                card.setSignature(signature);
            }

            event = new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.CARD_REBOUND, group.getId(), card.copyOf());
        }

        changed(event);
        return true;
    }

    /**
     * Sets the card volume.
     * @param cardId to update
     * @param volume linear gain in the range 0.0 - 1.0 (constrained to the range)
     * @return true if the card was found.
     */
    public boolean setCardVolume(String cardId, float volume)
    {
        return updateCard(cardId, card -> card.setVolume(volume));
    }

    /**
     * Sets the card muted state.
     * @param cardId to update
     * @param muted state
     * @return true if the card was found.
     */
    public boolean setCardMuted(String cardId, boolean muted)
    {
        return updateCard(cardId, card -> card.setMuted(muted));
    }

    /**
     * Sets the card stereo pan.
     * @param cardId to update
     * @param pan setting (null is treated as BOTH)
     * @return true if the card was found.
     */
    public boolean setCardPan(String cardId, AudioPan pan)
    {
        return updateCard(cardId, card -> card.setPan(pan));
    }

    /**
     * Sets the card's clear audio (encryption slip) alert setting.
     * @param cardId to update
     * @param clearAudioAlert setting (null is treated as AUTO)
     * @return true if the card was found.
     */
    public boolean setCardClearAudioAlert(String cardId, ClearAudioAlert clearAudioAlert)
    {
        return updateCard(cardId, card -> card.setClearAudioAlert(clearAudioAlert));
    }

    /**
     * Records a completed call on the card for learning whether the card's talkgroup is normally encrypted (see
     * ConsoleCard.recordCall()).
     * @param cardId to update
     * @param encrypted true if the call was encrypted
     * @return true if the card was found.
     */
    public boolean recordCardCall(String cardId, boolean encrypted)
    {
        return updateCard(cardId, card -> card.recordCall(encrypted));
    }

    /**
     * Applies the update to the card and broadcasts a CARD_CHANGED event when the card settings change.
     * @param cardId to update
     * @param update to apply
     * @return true if the card was found.
     */
    private boolean updateCard(String cardId, Consumer<ConsoleCard> update)
    {
        ConsoleLayoutEvent event;

        synchronized(this)
        {
            ConsoleGroup group = mLayout.getGroupForCard(cardId);

            if(group == null)
            {
                return false;
            }

            ConsoleCard card = group.getCard(cardId);
            ConsoleCard previous = card.copyOf();

            update.accept(card);

            if(card.hasSameState(previous))
            {
                return true;
            }

            event = new ConsoleLayoutEvent(ConsoleLayoutEvent.Type.CARD_CHANGED, group.getId(), card.copyOf());
        }

        changed(event);
        return true;
    }

    /**
     * Schedules a layout save and broadcasts the event.
     */
    private void changed(ConsoleLayoutEvent event)
    {
        scheduleSave();
        broadcast(event);
    }

    /**
     * Schedules a layout save and broadcasts the events.
     */
    private void changed(List<ConsoleLayoutEvent> events)
    {
        scheduleSave();

        for(ConsoleLayoutEvent event: events)
        {
            broadcast(event);
        }
    }

    /**
     * Saves the layout and resets the save pending flag.  The flag is reset before saving so that a change that occurs
     * during the save schedules another save.
     */
    public class SaveTask implements Runnable
    {
        @Override
        public void run()
        {
            synchronized(mSaveFutureLock)
            {
                mSaveFuture = null;
            }

            mSavePending.set(false);
            save();
        }
    }
}

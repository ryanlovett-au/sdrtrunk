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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the console layout (groups of alias-bound cards and console settings) and persists the layout as JSON to
 * the console_layout.json file in the 'console' folder under the application root directory.
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
    public static final String CONSOLE_DIRECTORY = "console";
    public static final String LAYOUT_FILE_NAME = "console_layout.json";
    public static final String DEFAULT_GROUP_NAME = "Group";
    private static final String TEMPORARY_FILE_SUFFIX = ".tmp";
    private static final String CORRUPT_FILE_SUFFIX = ".corrupt.";
    private static final long SAVE_DELAY_MS = 2000;
    private static final DateTimeFormatter CORRUPT_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Supplier<Path> mLayoutPathSupplier;
    private final ObjectMapper mObjectMapper;
    private final Broadcaster<ConsoleLayoutEvent> mBroadcaster = new Broadcaster<>();
    private final Object mSaveLock = new Object();
    private final Object mSaveFutureLock = new Object();
    private final AtomicBoolean mSavePending = new AtomicBoolean();
    private ScheduledFuture<?> mSaveFuture;
    private ConsoleLayout mLayout = new ConsoleLayout();

    /**
     * Constructs an instance that uses the console layout file in the application root directory specified in the
     * user preferences.  Invoke load() to load the layout from disk.
     *
     * @param userPreferences for resolving the application root directory
     */
    public ConsoleLayoutManager(UserPreferences userPreferences)
    {
        this(() -> getDefaultLayoutPath(userPreferences));
    }

    /**
     * Constructs an instance that uses the specified layout file.  Invoke load() to load the layout from disk.
     *
     * @param layoutPath for the layout file
     */
    public ConsoleLayoutManager(Path layoutPath)
    {
        this(() -> layoutPath);
    }

    /**
     * Constructs an instance
     * @param layoutPathSupplier to resolve the layout file path at load/save time
     */
    private ConsoleLayoutManager(Supplier<Path> layoutPathSupplier)
    {
        mLayoutPathSupplier = layoutPathSupplier;
        mObjectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Default console layout file path: [application root]/console/console_layout.json
     * @param userPreferences for resolving the application root directory
     * @return layout file path
     */
    public static Path getDefaultLayoutPath(UserPreferences userPreferences)
    {
        return userPreferences.getDirectoryPreference().getDirectoryApplicationRoot().resolve(CONSOLE_DIRECTORY)
            .resolve(LAYOUT_FILE_NAME);
    }

    /**
     * Current console layout file path
     */
    public Path getLayoutPath()
    {
        return mLayoutPathSupplier.get();
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
     * Loads the console layout from the layout file, replacing the current layout.  A missing file results in an empty
     * layout.  A corrupt file is renamed (kept as a backup) and results in an empty layout.
     */
    public void load()
    {
        Path path = getLayoutPath();
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
        }

        synchronized(this)
        {
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
        ConsoleLayout snapshot = getLayout();
        Path path = getLayoutPath();

        if(path == null)
        {
            mLog.error("Unable to save console layout - layout file path is null");
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
            float volume = card.getVolume();
            boolean muted = card.isMuted();
            AudioPan pan = card.getPan();

            update.accept(card);

            if(card.getVolume() == volume && card.isMuted() == muted && card.getPan() == pan)
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

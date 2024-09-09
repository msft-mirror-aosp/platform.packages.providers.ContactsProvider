/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.contacts;

import static com.android.providers.contacts.flags.Flags.cp2AccountMoveFlag;
import static com.android.providers.contacts.flags.Flags.cp2AccountMoveSyncStubFlag;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.providers.contacts.util.NeededForTesting;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A class to move {@link RawContacts} and {@link Groups} from one account to another.
 */
@NeededForTesting
public class ContactMover {
    private static final String TAG = "ContactMover";
    private final ContactsDatabaseHelper mDbHelper;
    private final ContactsProvider2 mCp2;

    @NeededForTesting
    public ContactMover(ContactsProvider2 contactsProvider,
            ContactsDatabaseHelper contactsDatabaseHelper) {
        mCp2 = contactsProvider;
        mDbHelper = contactsDatabaseHelper;
    }

    private void updateRawContactsAccount(
            AccountWithDataSet destAccount, Set<Long> rawContactIds) {
        if (rawContactIds.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(RawContacts.ACCOUNT_NAME, destAccount.getAccountName());
        values.put(RawContacts.ACCOUNT_TYPE, destAccount.getAccountType());
        values.put(RawContacts.DATA_SET, destAccount.getDataSet());
        values.putNull(RawContacts.SOURCE_ID);
        values.putNull(RawContacts.SYNC1);
        values.putNull(RawContacts.SYNC2);
        values.putNull(RawContacts.SYNC3);
        values.putNull(RawContacts.SYNC4);

        // actually update the account columns and break the source ID
        mCp2.update(
                RawContacts.CONTENT_URI,
                values,
                RawContacts._ID + " IN (" + TextUtils.join(",", rawContactIds) + ")",
                new String[] {});
    }

    private void updateGroupAccount(
            AccountWithDataSet destAccount, Set<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(Groups.ACCOUNT_NAME, destAccount.getAccountName());
        values.put(Groups.ACCOUNT_TYPE, destAccount.getAccountType());
        values.put(Groups.DATA_SET, destAccount.getDataSet());
        values.putNull(Groups.SOURCE_ID);
        values.putNull(Groups.SYNC1);
        values.putNull(Groups.SYNC2);
        values.putNull(Groups.SYNC3);
        values.putNull(Groups.SYNC4);

        // actually update the account columns and break the source ID
        mCp2.update(
                Groups.CONTENT_URI,
                values,
                Groups._ID + " IN (" + TextUtils.join(",", groupIds) + ")",
                new String[] {});
    }

    private void updateGroupDataRows(Map<Long, Long> groupIdMap) {
        // for each group in the groupIdMap, update all Group Membership data rows from key to value
        for (Map.Entry<Long, Long> groupIds: groupIdMap.entrySet()) {
            mDbHelper.updateGroupMemberships(groupIds.getKey(), groupIds.getValue());
        }

    }

    private boolean isAccountTypeMatch(
            AccountWithDataSet sourceAccount, AccountWithDataSet destAccount) {
        if (sourceAccount.getAccountType() == null) {
            return destAccount.getAccountType() == null;
        }

        return sourceAccount.getAccountType().equals(destAccount.getAccountType());
    }

    private boolean isDataSetMatch(
            AccountWithDataSet sourceAccount, AccountWithDataSet destAccount) {
        if (sourceAccount.getDataSet() == null) {
            return destAccount.getDataSet() == null;
        }

        return sourceAccount.getDataSet().equals(destAccount.getDataSet());
    }

    private void moveNonSystemGroups(AccountWithDataSet sourceAccount,
            AccountWithDataSet destAccount, boolean insertSyncStubs) {
        Pair<Set<Long>, Map<Long, Long>> nonSystemGroups = mDbHelper
                .deDuplicateGroups(sourceAccount, destAccount, /* isSystemGroupQuery= */ false);
        Set<Long> nonSystemUniqueGroups = nonSystemGroups.first;
        Map<Long, Long> nonSystemDuplicateGroupMap = nonSystemGroups.second;

        // For non-system groups that are duplicated in source and dest:
        // 1. update contact data rows (to point do the group in dest)
        // 2. Set deleted = 1 for dupe groups in source
        updateGroupDataRows(nonSystemDuplicateGroupMap);
        for (Map.Entry<Long, Long> groupIds: nonSystemDuplicateGroupMap.entrySet()) {
            mCp2.deleteGroup(Groups.CONTENT_URI, groupIds.getKey(), false);
        }

        // For non-system groups that only exist in source:
        // 1. Write sync stubs for synced groups (if needed)
        // 2. Update account ids
        if (!sourceAccount.isLocalAccount() && insertSyncStubs) {
            mDbHelper.insertGroupSyncStubs(sourceAccount, nonSystemUniqueGroups);
        }
        updateGroupAccount(destAccount, nonSystemUniqueGroups);
    }

    private void moveSystemGroups(
            AccountWithDataSet sourceAccount, AccountWithDataSet destAccount) {
        Pair<Set<Long>, Map<Long, Long>> systemGroups = mDbHelper
                .deDuplicateGroups(sourceAccount, destAccount, /* isSystemGroupQuery= */ true);
        Set<Long> systemUniqueGroups = systemGroups.first;
        Map<Long, Long> systemDuplicateGroupMap = systemGroups.second;

        // For system groups in source that have a match in dest:
        // 1. Update contact data rows (can't delete the existing groups)
        updateGroupDataRows(systemDuplicateGroupMap);

        // For system groups that only exist in source:
        // 1. Get content values for the relevant (non-empty) groups
        // 2. Create a group in destination account (while building an ID map)
        // 3. Update contact data rows to point at the new group(s)
        Map<Long, ContentValues> oldIdToNewValues = mDbHelper
                .getGroupContentValuesForMoveCopy(destAccount, systemUniqueGroups);
        Map<Long, Long> systemGroupIdMap = new HashMap<>();
        for (Map.Entry<Long, ContentValues> idToValues: oldIdToNewValues.entrySet()) {
            Uri newGroupUri = mCp2.insert(Groups.CONTENT_URI, idToValues.getValue());
            if (newGroupUri != null) {
                Long newGroupId = ContentUris.parseId(newGroupUri);
                systemGroupIdMap.put(idToValues.getKey(), newGroupId);
            }
        }
        updateGroupDataRows(systemGroupIdMap);

        // now delete membership data rows for any unique groups we skipped - otherwise the contacts
        // will be left with data rows pointing to the skipped groups in the source account.
        if (!oldIdToNewValues.isEmpty()) {
            systemUniqueGroups.removeAll(oldIdToNewValues.keySet());
        }
        mCp2.delete(Data.CONTENT_URI,
                CommonDataKinds.GroupMembership.GROUP_ROW_ID
                        + " IN (" + TextUtils.join(",", systemUniqueGroups) + ")"
                        + " AND " + Data.MIMETYPE + " = ?",
                new String[] {CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE}
        );
    }

    private void moveGroups(AccountWithDataSet sourceAccount, AccountWithDataSet destAccount,
            boolean createSyncStubs) {
        moveNonSystemGroups(sourceAccount, destAccount, createSyncStubs);
        moveSystemGroups(sourceAccount, destAccount);
    }

    /**
     * Moves {@link RawContacts} and {@link Groups} from one account to another.
     * @param sourceAccount the source {@link AccountWithDataSet} to move contacts and groups from.
     * @param destAccount the destination {@link AccountWithDataSet} to move contacts and groups to.
     */
    @NeededForTesting
    void moveRawContacts(AccountWithDataSet sourceAccount, AccountWithDataSet destAccount) {
        if (!cp2AccountMoveFlag()) {
            Log.w(TAG, "moveRawContacts: flag disabled");
            return;
        }
        moveRawContactsInternal(sourceAccount, destAccount, /* insertSyncStubs= */ false);
    }

    /**
     * Moves {@link RawContacts} and {@link Groups} from one account to another, while writing sync
     * stubs in the source account to notify relevant sync adapters in the source account of the
     * move.
     * @param sourceAccount the source {@link AccountWithDataSet} to move contacts and groups from.
     * @param destAccount the destination {@link AccountWithDataSet} to move contacts and groups to.
     */
    @NeededForTesting
    void moveRawContactsWithSyncStubs(AccountWithDataSet sourceAccount,
            AccountWithDataSet destAccount) {
        if (!cp2AccountMoveFlag() || !cp2AccountMoveSyncStubFlag()) {
            Log.w(TAG, "moveRawContactsWithSyncStubs: flags disabled");
            return;
        }
        moveRawContactsInternal(sourceAccount, destAccount, /* insertSyncStubs= */ true);
    }

    private void moveRawContactsInternal(AccountWithDataSet sourceAccount,
            AccountWithDataSet destAccount, boolean insertSyncStubs) {
        if (sourceAccount.equals(destAccount)) {
            throw new IllegalArgumentException("Source and destination accounts must differ");
        }


        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            // If we are moving between account types or data sets, delete non-portable data rows
            // from the source
            if (!isAccountTypeMatch(sourceAccount, destAccount)
                    || !isDataSetMatch(sourceAccount, destAccount)) {
                mDbHelper.deleteNonCommonDataRows(sourceAccount);
            }

            // Move any groups and group memberships from the source to destination account
            moveGroups(sourceAccount, destAccount, insertSyncStubs);

            // Next, compare raw contacts from source and destination accounts, find the unique
            // raw contacts from source account;
            Pair<Set<Long>, Set<Long>> sourceRawContactIds =
                    mDbHelper.deDuplicateRawContacts(sourceAccount, destAccount);
            Set<Long> nonDuplicates = sourceRawContactIds.first;
            Set<Long> duplicates = sourceRawContactIds.second;

            if (!sourceAccount.isLocalAccount() && insertSyncStubs) {
                /*
                    If the source account isn't a device account, and we want to write stub contacts
                    for the move, create them now.
                    This ensures any sync adapters on the source account won't just sync the moved
                    contacts back down (creating duplicates).
                 */
                mDbHelper.insertRawContactSyncStubs(sourceAccount, nonDuplicates);
            }

            // move the contacts to the destination account
            updateRawContactsAccount(destAccount, nonDuplicates);

            // Last, clear the duplicates.
            // Since these are duplicates, we don't need to do anything else with them
            for (long rawContactId: duplicates) {
                mCp2.deleteRawContact(
                        rawContactId,
                        mDbHelper.getContactId(rawContactId),
                        false);
            }


            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

}
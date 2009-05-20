/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.view.menu;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of the {@link android.view.Menu} interface for creating a
 * standard menu UI.
 */
public class MenuBuilder implements Menu {
    private static final String LOGTAG = "MenuBuilder";
    
    /** The number of different menu types */
    public static final int NUM_TYPES = 3;
    /** The menu type that represents the icon menu view */
    public static final int TYPE_ICON = 0;
    /** The menu type that represents the expanded menu view */
    public static final int TYPE_EXPANDED = 1;
    /**
     * The menu type that represents a menu dialog. Examples are context and sub
     * menus. This menu type will not have a corresponding MenuView, but it will
     * have an ItemView.
     */
    public static final int TYPE_DIALOG = 2;

    private static final String VIEWS_TAG = "android:views";
    
    // Order must be the same order as the TYPE_*
    static final int THEME_RES_FOR_TYPE[] = new int[] {
        com.android.internal.R.style.Theme_IconMenu,
        com.android.internal.R.style.Theme_ExpandedMenu,
        0,
    };
    
    // Order must be the same order as the TYPE_*
    static final int LAYOUT_RES_FOR_TYPE[] = new int[] {
        com.android.internal.R.layout.icon_menu_layout,
        com.android.internal.R.layout.expanded_menu_layout,
        0,
    };

    // Order must be the same order as the TYPE_*
    static final int ITEM_LAYOUT_RES_FOR_TYPE[] = new int[] {
        com.android.internal.R.layout.icon_menu_item_layout,
        com.android.internal.R.layout.list_menu_item_layout,
        com.android.internal.R.layout.list_menu_item_layout,
    };

    private static final int[]  sCategoryToOrder = new int[] {
        1, /* No category */
        4, /* CONTAINER */
        5, /* SYSTEM */
        3, /* SECONDARY */
        2, /* ALTERNATIVE */
        0, /* SELECTED_ALTERNATIVE */
    };

    private final Context mContext;
    private final Resources mResources;

    /**
     * Whether the shortcuts should be qwerty-accessible. Use isQwertyMode()
     * instead of accessing this directly.
     */
    private boolean mQwertyMode;

    /**
     * Whether the shortcuts should be visible on menus. Use isShortcutsVisible()
     * instead of accessing this directly.
     */ 
    private boolean mShortcutsVisible;
    
    /**
     * Callback that will receive the various menu-related events generated by
     * this class. Use getCallback to get a reference to the callback.
     */
    private Callback mCallback;
    
    /** Contains all of the items for this menu */
    private ArrayList<MenuItemImpl> mItems;

    /** Contains only the items that are currently visible.  This will be created/refreshed from
     * {@link #getVisibleItems()} */
    private ArrayList<MenuItemImpl> mVisibleItems;
    /**
     * Whether or not the items (or any one item's shown state) has changed since it was last
     * fetched from {@link #getVisibleItems()}
     */ 
    private boolean mIsVisibleItemsStale;

    /**
     * Current use case is Context Menus: As Views populate the context menu, each one has
     * extra information that should be passed along.  This is the current menu info that
     * should be set on all items added to this menu.
     */
    private ContextMenuInfo mCurrentMenuInfo;
    
    /** Header title for menu types that have a header (context and submenus) */
    CharSequence mHeaderTitle;
    /** Header icon for menu types that have a header and support icons (context) */
    Drawable mHeaderIcon;
    /** Header custom view for menu types that have a header and support custom views (context) */
    View mHeaderView;

    /**
     * Contains the state of the View hierarchy for all menu views when the menu
     * was frozen.
     */
    private SparseArray<Parcelable> mFrozenViewStates;

    /**
     * Prevents onItemsChanged from doing its junk, useful for batching commands
     * that may individually call onItemsChanged.
     */
    private boolean mPreventDispatchingItemsChanged = false;
    
    private boolean mOptionalIconsVisible = false;
    
    private MenuType[] mMenuTypes;
    class MenuType {
        private int mMenuType;
        
        /** The layout inflater that uses the menu type's theme */
        private LayoutInflater mInflater;

        /** The lazily loaded {@link MenuView} */
        private WeakReference<MenuView> mMenuView;

        MenuType(int menuType) {
            mMenuType = menuType;
        }
        
        LayoutInflater getInflater() {
            // Create an inflater that uses the given theme for the Views it inflates
            if (mInflater == null) {
                Context wrappedContext = new ContextThemeWrapper(getContext(),
                        THEME_RES_FOR_TYPE[mMenuType]); 
                mInflater = (LayoutInflater) wrappedContext
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            }
            
            return mInflater;
        }
        
        MenuView getMenuView(ViewGroup parent) {
            if (LAYOUT_RES_FOR_TYPE[mMenuType] == 0) {
                return null;
            }

            synchronized (this) {
                MenuView menuView = mMenuView != null ? mMenuView.get() : null;
                
                if (menuView == null) {
                    menuView = (MenuView) getInflater().inflate(
                            LAYOUT_RES_FOR_TYPE[mMenuType], parent, false);
                    menuView.initialize(MenuBuilder.this, mMenuType);

                    // Cache the view
                    mMenuView = new WeakReference<MenuView>(menuView);
                    
                    if (mFrozenViewStates != null) {
                        View view = (View) menuView;
                        view.restoreHierarchyState(mFrozenViewStates);

                        // Clear this menu type's frozen state, since we just restored it
                        mFrozenViewStates.remove(view.getId());
                    }
                }
            
                return menuView;
            }
        }
        
        boolean hasMenuView() {
            return mMenuView != null && mMenuView.get() != null;
        }
    }
    
    /**
     * Called by menu to notify of close and selection changes
     */
    public interface Callback {
        /**
         * Called when a menu item is selected.
         * @param menu The menu that is the parent of the item
         * @param item The menu item that is selected
         * @return whether the menu item selection was handled
         */
        public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item);
        
        /**
         * Called when a menu is closed.
         * @param menu The menu that was closed.
         * @param allMenusAreClosing Whether the menus are completely closing (true),
         *            or whether there is another menu opening shortly
         *            (false). For example, if the menu is closing because a
         *            sub menu is about to be shown, <var>allMenusAreClosing</var>
         *            is false.
         */
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing);
        
        /**
         * Called when a sub menu is selected.  This is a cue to open the given sub menu's decor.
         * @param subMenu the sub menu that is being opened
         * @return whether the sub menu selection was handled by the callback
         */
        public boolean onSubMenuSelected(SubMenuBuilder subMenu);

        /**
         * Called when a sub menu is closed
         * @param menu the sub menu that was closed
         */
        public void onCloseSubMenu(SubMenuBuilder menu);
        
        /**
         * Called when the mode of the menu changes (for example, from icon to expanded).
         * 
         * @param menu the menu that has changed modes
         */
        public void onMenuModeChange(MenuBuilder menu);
    }

    /**
     * Called by menu items to execute their associated action
     */
    public interface ItemInvoker {
        public boolean invokeItem(MenuItemImpl item);
    }

    public MenuBuilder(Context context) {
        mMenuTypes = new MenuType[NUM_TYPES];
        
        mContext = context;
        mResources = context.getResources();
        
        mItems = new ArrayList<MenuItemImpl>();
        
        mVisibleItems = new ArrayList<MenuItemImpl>();
        mIsVisibleItemsStale = true;
        
        mShortcutsVisible =
                (mResources.getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS);
    }
    
    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    MenuType getMenuType(int menuType) {
        if (mMenuTypes[menuType] == null) {
            mMenuTypes[menuType] = new MenuType(menuType);
        }
        
        return mMenuTypes[menuType];
    }
    
    /**
     * Gets a menu View that contains this menu's items.
     * 
     * @param menuType The type of menu to get a View for (must be one of
     *            {@link #TYPE_ICON}, {@link #TYPE_EXPANDED},
     *            {@link #TYPE_DIALOG}).
     * @param parent The ViewGroup that provides a set of LayoutParams values
     *            for this menu view
     * @return A View for the menu of type <var>menuType</var>
     */
    public View getMenuView(int menuType, ViewGroup parent) {
        // The expanded menu depends on the number if items shown in the icon menu (which
        // is adjustable as setters/XML attributes on IconMenuView [imagine a larger LCD
        // wanting to show more icons]). If, for example, the activity goes through
        // an orientation change while the expanded menu is open, the icon menu's view
        // won't have an instance anymore; so here we make sure we have an icon menu view (matching
        // the same parent so the layout parameters from the XML are used). This
        // will create the icon menu view and cache it (if it doesn't already exist). 
        if (menuType == TYPE_EXPANDED
                && (mMenuTypes[TYPE_ICON] == null || !mMenuTypes[TYPE_ICON].hasMenuView())) {
            getMenuType(TYPE_ICON).getMenuView(parent);
        }
        
        return (View) getMenuType(menuType).getMenuView(parent);
    }
    
    private int getNumIconMenuItemsShown() {
        ViewGroup parent = null;
        
        if (!mMenuTypes[TYPE_ICON].hasMenuView()) {
            /*
             * There isn't an icon menu view instantiated, so when we get it
             * below, it will lazily instantiate it. We should pass a proper
             * parent so it uses the layout_ attributes present in the XML
             * layout file.
             */
            if (mMenuTypes[TYPE_EXPANDED].hasMenuView()) {
                View expandedMenuView = (View) mMenuTypes[TYPE_EXPANDED].getMenuView(null);
                parent = (ViewGroup) expandedMenuView.getParent();
            }
        }
        
        return ((IconMenuView) getMenuView(TYPE_ICON, parent)).getNumActualItemsShown(); 
    }
    
    /**
     * Clears the cached menu views. Call this if the menu views need to another
     * layout (for example, if the screen size has changed).
     */
    public void clearMenuViews() {
        for (int i = NUM_TYPES - 1; i >= 0; i--) {
            if (mMenuTypes[i] != null) {
                mMenuTypes[i].mMenuView = null;
            }
        }
        
        for (int i = mItems.size() - 1; i >= 0; i--) {
            MenuItemImpl item = mItems.get(i);
            if (item.hasSubMenu()) {
                ((SubMenuBuilder) item.getSubMenu()).clearMenuViews();
            }
            item.clearItemViews();
        }
    }
    
    /**
     * Adds an item to the menu.  The other add methods funnel to this.
     */
    private MenuItem addInternal(int group, int id, int categoryOrder, CharSequence title) {
        final int ordering = getOrdering(categoryOrder);
        
        final MenuItemImpl item = new MenuItemImpl(this, group, id, categoryOrder, ordering, title);

        if (mCurrentMenuInfo != null) {
            // Pass along the current menu info
            item.setMenuInfo(mCurrentMenuInfo);
        }
        
        mItems.add(findInsertIndex(mItems, ordering), item);
        onItemsChanged(false);
        
        return item;
    }
    
    public MenuItem add(CharSequence title) {
        return addInternal(0, 0, 0, title);
    }

    public MenuItem add(int titleRes) {
        return addInternal(0, 0, 0, mResources.getString(titleRes));
    }

    public MenuItem add(int group, int id, int categoryOrder, CharSequence title) {
        return addInternal(group, id, categoryOrder, title);
    }

    public MenuItem add(int group, int id, int categoryOrder, int title) {
        return addInternal(group, id, categoryOrder, mResources.getString(title));
    }

    public SubMenu addSubMenu(CharSequence title) {
        return addSubMenu(0, 0, 0, title);
    }

    public SubMenu addSubMenu(int titleRes) {
        return addSubMenu(0, 0, 0, mResources.getString(titleRes));
    }

    public SubMenu addSubMenu(int group, int id, int categoryOrder, CharSequence title) {
        final MenuItemImpl item = (MenuItemImpl) addInternal(group, id, categoryOrder, title);
        final SubMenuBuilder subMenu = new SubMenuBuilder(mContext, this, item);
        item.setSubMenu(subMenu);
        
        return subMenu;
    }

    public SubMenu addSubMenu(int group, int id, int categoryOrder, int title) {
        return addSubMenu(group, id, categoryOrder, mResources.getString(title));
    }

    public int addIntentOptions(int group, int id, int categoryOrder, ComponentName caller,
            Intent[] specifics, Intent intent, int flags, MenuItem[] outSpecificItems) {
        PackageManager pm = mContext.getPackageManager();
        final List<ResolveInfo> lri =
                pm.queryIntentActivityOptions(caller, specifics, intent, 0);
        final int N = lri != null ? lri.size() : 0;

        if ((flags & FLAG_APPEND_TO_GROUP) == 0) {
            removeGroup(group);
        }

        for (int i=0; i<N; i++) {
            final ResolveInfo ri = lri.get(i);
            Intent rintent = new Intent(
                ri.specificIndex < 0 ? intent : specifics[ri.specificIndex]);
            rintent.setComponent(new ComponentName(
                    ri.activityInfo.applicationInfo.packageName,
                    ri.activityInfo.name));
            final MenuItem item = add(group, id, categoryOrder, ri.loadLabel(pm))
                    .setIcon(ri.loadIcon(pm))
                    .setIntent(rintent);
            if (outSpecificItems != null && ri.specificIndex >= 0) {
                outSpecificItems[ri.specificIndex] = item;
            }
        }

        return N;
    }

    public void removeItem(int id) {
        removeItemAtInt(findItemIndex(id), true);
    }

    public void removeGroup(int group) {
        final int i = findGroupIndex(group);

        if (i >= 0) {
            final int maxRemovable = mItems.size() - i;
            int numRemoved = 0;
            while ((numRemoved++ < maxRemovable) && (mItems.get(i).getGroupId() == group)) {
                // Don't force update for each one, this method will do it at the end
                removeItemAtInt(i, false);
            }
            
            // Notify menu views
            onItemsChanged(false);
        }
    }

    /**
     * Remove the item at the given index and optionally forces menu views to
     * update.
     * 
     * @param index The index of the item to be removed. If this index is
     *            invalid an exception is thrown.
     * @param updateChildrenOnMenuViews Whether to force update on menu views.
     *            Please make sure you eventually call this after your batch of
     *            removals.
     */
    private void removeItemAtInt(int index, boolean updateChildrenOnMenuViews) {
        if ((index < 0) || (index >= mItems.size())) return;

        mItems.remove(index);
        
        if (updateChildrenOnMenuViews) onItemsChanged(false);
    }
    
    public void removeItemAt(int index) {
        removeItemAtInt(index, true);
    }

    public void clearAll() {
        mPreventDispatchingItemsChanged = true;
        clear();
        clearHeader();
        mPreventDispatchingItemsChanged = false;
        onItemsChanged(true);
    }
    
    public void clear() {
        mItems.clear();
        
        onItemsChanged(true);
    }

    void setExclusiveItemChecked(MenuItem item) {
        final int group = item.getGroupId();
        
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            MenuItemImpl curItem = mItems.get(i);
            if (curItem.getGroupId() == group) {
                if (!curItem.isExclusiveCheckable()) continue;
                if (!curItem.isCheckable()) continue;
                
                // Check the item meant to be checked, uncheck the others (that are in the group)
                curItem.setCheckedInt(curItem == item);
            }
        }
    }
    
    public void setGroupCheckable(int group, boolean checkable, boolean exclusive) {
        final int N = mItems.size();
       
        for (int i = 0; i < N; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.getGroupId() == group) {
                item.setExclusiveCheckable(exclusive);
                item.setCheckable(checkable);
            }
        }
    }

    public void setGroupVisible(int group, boolean visible) {
        final int N = mItems.size();

        // We handle the notification of items being changed ourselves, so we use setVisibleInt rather
        // than setVisible and at the end notify of items being changed
        
        boolean changedAtLeastOneItem = false;
        for (int i = 0; i < N; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.getGroupId() == group) {
                if (item.setVisibleInt(visible)) changedAtLeastOneItem = true;
            }
        }

        if (changedAtLeastOneItem) onItemsChanged(false);
    }

    public void setGroupEnabled(int group, boolean enabled) {
        final int N = mItems.size();

        for (int i = 0; i < N; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.getGroupId() == group) {
                item.setEnabled(enabled);
            }
        }
    }

    public boolean hasVisibleItems() {
        final int size = size();

        for (int i = 0; i < size; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.isVisible()) {
                return true;
            }
        }

        return false;
    }

    public MenuItem findItem(int id) {
        final int size = size();
        for (int i = 0; i < size; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.getItemId() == id) {
                return item;
            } else if (item.hasSubMenu()) {
                MenuItem possibleItem = item.getSubMenu().findItem(id);
                
                if (possibleItem != null) {
                    return possibleItem;
                }
            }
        }
        
        return null;
    }

    public int findItemIndex(int id) {
        final int size = size();

        for (int i = 0; i < size; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.getItemId() == id) {
                return i;
            }
        }

        return -1;
    }

    public int findGroupIndex(int group) {
        return findGroupIndex(group, 0);
    }

    public int findGroupIndex(int group, int start) {
        final int size = size();
        
        if (start < 0) {
            start = 0;
        }
        
        for (int i = start; i < size; i++) {
            final MenuItemImpl item = mItems.get(i);
            
            if (item.getGroupId() == group) {
                return i;
            }
        }

        return -1;
    }
    
    public int size() {
        return mItems.size();
    }

    /** {@inheritDoc} */
    public MenuItem getItem(int index) {
        return mItems.get(index);
    }

    public boolean isShortcutKey(int keyCode, KeyEvent event) {
        return findItemWithShortcutForKey(keyCode, event) != null;
    }

    public void setQwertyMode(boolean isQwerty) {
        mQwertyMode = isQwerty;
        
        refreshShortcuts(isShortcutsVisible(), isQwerty);
    }

    /**
     * Returns the ordering across all items. This will grab the category from
     * the upper bits, find out how to order the category with respect to other
     * categories, and combine it with the lower bits.
     * 
     * @param categoryOrder The category order for a particular item (if it has
     *            not been or/add with a category, the default category is
     *            assumed).
     * @return An ordering integer that can be used to order this item across
     *         all the items (even from other categories).
     */
    private static int getOrdering(int categoryOrder)
    {
        final int index = (categoryOrder & CATEGORY_MASK) >> CATEGORY_SHIFT;
        
        if (index < 0 || index >= sCategoryToOrder.length) {
            throw new IllegalArgumentException("order does not contain a valid category.");
        }
        
        return (sCategoryToOrder[index] << CATEGORY_SHIFT) | (categoryOrder & USER_MASK);
    }

    /**
     * @return whether the menu shortcuts are in qwerty mode or not
     */
    boolean isQwertyMode() {
        return mQwertyMode;
    }

    /**
     * Refreshes the shortcut labels on each of the displayed items.  Passes the arguments
     * so submenus don't need to call their parent menu for the same values.
     */
    private void refreshShortcuts(boolean shortcutsVisible, boolean qwertyMode) {
        MenuItemImpl item;
        for (int i = mItems.size() - 1; i >= 0; i--) {
            item = mItems.get(i);
            
            if (item.hasSubMenu()) {
                ((MenuBuilder) item.getSubMenu()).refreshShortcuts(shortcutsVisible, qwertyMode);
            }
            
            item.refreshShortcutOnItemViews(shortcutsVisible, qwertyMode);
        }
    }

    /**
     * Sets whether the shortcuts should be visible on menus.  Devices without hardware
     * key input will never make shortcuts visible even if this method is passed 'true'.
     * 
     * @param shortcutsVisible Whether shortcuts should be visible (if true and a
     *            menu item does not have a shortcut defined, that item will
     *            still NOT show a shortcut)
     */
    public void setShortcutsVisible(boolean shortcutsVisible) {
        if (mShortcutsVisible == shortcutsVisible) return;
        
        mShortcutsVisible =
            (mResources.getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS)
            && shortcutsVisible;
        
        refreshShortcuts(mShortcutsVisible, isQwertyMode());
    }

    /**
     * @return Whether shortcuts should be visible on menus.
     */
    public boolean isShortcutsVisible() {
        return mShortcutsVisible;
    }
    
    Resources getResources() {
        return mResources;
    }

    public Callback getCallback() {
        return mCallback;
    }
    
    public Context getContext() {
        return mContext;
    }
    
    private static int findInsertIndex(ArrayList<MenuItemImpl> items, int ordering) {
        for (int i = items.size() - 1; i >= 0; i--) {
            MenuItemImpl item = items.get(i);
            if (item.getOrdering() <= ordering) {
                return i + 1;
            }
        }
        
        return 0;
    }
    
    public boolean performShortcut(int keyCode, KeyEvent event, int flags) {
        final MenuItemImpl item = findItemWithShortcutForKey(keyCode, event);

        boolean handled = false;
        
        if (item != null) {
            handled = performItemAction(item, flags);
        }
        
        if ((flags & FLAG_ALWAYS_PERFORM_CLOSE) != 0) {
            close(true);
        }
        
        return handled;
    }

    MenuItemImpl findItemWithShortcutForKey(int keyCode, KeyEvent event) {
        final boolean qwerty = isQwertyMode();
        final int metaState = event.getMetaState();
        final KeyCharacterMap.KeyData possibleChars = new KeyCharacterMap.KeyData();
        // Get the chars associated with the keyCode (i.e using any chording combo)
        final boolean isKeyCodeMapped = event.getKeyData(possibleChars);
        // The delete key is not mapped to '\b' so we treat it specially
        if (!isKeyCodeMapped && (keyCode != KeyEvent.KEYCODE_DEL)) {
            return null;
        }

        // Look for an item whose shortcut is this key.
        final int N = mItems.size();
        for (int i = 0; i < N; i++) {
            MenuItemImpl item = mItems.get(i);
            if (item.hasSubMenu()) {
                MenuItemImpl subMenuItem = ((MenuBuilder)item.getSubMenu())
                                .findItemWithShortcutForKey(keyCode, event);
                if (subMenuItem != null) {
                    return subMenuItem;
                }
            }
            if (qwerty) {
                final char shortcutAlphaChar = item.getAlphabeticShortcut();
                if (((metaState & (KeyEvent.META_SHIFT_ON | KeyEvent.META_SYM_ON)) == 0) &&
                        (shortcutAlphaChar != 0) &&
                        (shortcutAlphaChar == possibleChars.meta[0]
                         || shortcutAlphaChar == possibleChars.meta[2]
                         || (shortcutAlphaChar == '\b' && keyCode == KeyEvent.KEYCODE_DEL)) &&
                        item.isEnabled()) {
                    return item;
                }
            } else {
                final char shortcutNumericChar = item.getNumericShortcut();
                if (((metaState & (KeyEvent.META_SHIFT_ON | KeyEvent.META_SYM_ON)) == 0) &&
                        (shortcutNumericChar != 0) &&
                        (shortcutNumericChar == possibleChars.meta[0]
                            || shortcutNumericChar == possibleChars.meta[2]) &&
                        item.isEnabled()) {
                    return item;
                }
            }
        }
        return null;
    }
    
    public boolean performIdentifierAction(int id, int flags) {
        // Look for an item whose identifier is the id.
        return performItemAction(findItem(id), flags);           
    }

    public boolean performItemAction(MenuItem item, int flags) {
        MenuItemImpl itemImpl = (MenuItemImpl) item;
        
        if (itemImpl == null || !itemImpl.isEnabled()) {
            return false;
        }        
        
        boolean invoked = itemImpl.invoke();

        if (item.hasSubMenu()) {
            close(false);

            if (mCallback != null) {
                // Return true if the sub menu was invoked or the item was invoked previously
                invoked = mCallback.onSubMenuSelected((SubMenuBuilder) item.getSubMenu())
                        || invoked;
            }
        } else {
            if ((flags & FLAG_PERFORM_NO_CLOSE) == 0) {
                close(true);
            }
        }
        
        return invoked;
    }
    
    /**
     * Closes the visible menu.
     * 
     * @param allMenusAreClosing Whether the menus are completely closing (true),
     *            or whether there is another menu coming in this menu's place
     *            (false). For example, if the menu is closing because a
     *            sub menu is about to be shown, <var>allMenusAreClosing</var>
     *            is false.
     */
    final void close(boolean allMenusAreClosing) {
        Callback callback = getCallback();
        if (callback != null) {
            callback.onCloseMenu(this, allMenusAreClosing);
        }
    }

    /** {@inheritDoc} */
    public void close() {
        close(true);
    }

    /**
     * Called when an item is added or removed.
     * 
     * @param cleared Whether the items were cleared or just changed.
     */
    private void onItemsChanged(boolean cleared) {
        if (!mPreventDispatchingItemsChanged) {
            if (mIsVisibleItemsStale == false) mIsVisibleItemsStale = true;
            
            MenuType[] menuTypes = mMenuTypes;
            for (int i = 0; i < NUM_TYPES; i++) {
                if ((menuTypes[i] != null) && (menuTypes[i].hasMenuView())) {
                    MenuView menuView = menuTypes[i].mMenuView.get();
                    menuView.updateChildren(cleared);
                }
            }
        }
    }

    /**
     * Called by {@link MenuItemImpl} when its visible flag is changed.
     * @param item The item that has gone through a visibility change.
     */
    void onItemVisibleChanged(MenuItemImpl item) {
        // Notify of items being changed
        onItemsChanged(false);
    }
    
    ArrayList<MenuItemImpl> getVisibleItems() {
        if (!mIsVisibleItemsStale) return mVisibleItems;
        
        // Refresh the visible items
        mVisibleItems.clear();
        
        final int itemsSize = mItems.size(); 
        MenuItemImpl item;
        for (int i = 0; i < itemsSize; i++) {
            item = mItems.get(i);
            if (item.isVisible()) mVisibleItems.add(item);
        }
        
        mIsVisibleItemsStale = false;
        
        return mVisibleItems;
    }

    public void clearHeader() {
        mHeaderIcon = null;
        mHeaderTitle = null;
        mHeaderView = null;
        
        onItemsChanged(false);
    }
    
    private void setHeaderInternal(final int titleRes, final CharSequence title, final int iconRes,
            final Drawable icon, final View view) {
        final Resources r = getResources();

        if (view != null) {
            mHeaderView = view;
            
            // If using a custom view, then the title and icon aren't used
            mHeaderTitle = null;
            mHeaderIcon = null;
        } else {
            if (titleRes > 0) {
                mHeaderTitle = r.getText(titleRes);
            } else if (title != null) {
                mHeaderTitle = title;
            }
            
            if (iconRes > 0) {
                mHeaderIcon = r.getDrawable(iconRes);
            } else if (icon != null) {
                mHeaderIcon = icon;
            }
            
            // If using the title or icon, then a custom view isn't used
            mHeaderView = null;
        }
        
        // Notify of change
        onItemsChanged(false);
    }

    /**
     * Sets the header's title. This replaces the header view. Called by the
     * builder-style methods of subclasses.
     * 
     * @param title The new title.
     * @return This MenuBuilder so additional setters can be called.
     */
    protected MenuBuilder setHeaderTitleInt(CharSequence title) {
        setHeaderInternal(0, title, 0, null, null);
        return this;
    }
    
    /**
     * Sets the header's title. This replaces the header view. Called by the
     * builder-style methods of subclasses.
     * 
     * @param titleRes The new title (as a resource ID).
     * @return This MenuBuilder so additional setters can be called.
     */
    protected MenuBuilder setHeaderTitleInt(int titleRes) {
        setHeaderInternal(titleRes, null, 0, null, null);
        return this;
    }
    
    /**
     * Sets the header's icon. This replaces the header view. Called by the
     * builder-style methods of subclasses.
     * 
     * @param icon The new icon.
     * @return This MenuBuilder so additional setters can be called.
     */
    protected MenuBuilder setHeaderIconInt(Drawable icon) {
        setHeaderInternal(0, null, 0, icon, null);
        return this;
    }
    
    /**
     * Sets the header's icon. This replaces the header view. Called by the
     * builder-style methods of subclasses.
     * 
     * @param iconRes The new icon (as a resource ID).
     * @return This MenuBuilder so additional setters can be called.
     */
    protected MenuBuilder setHeaderIconInt(int iconRes) {
        setHeaderInternal(0, null, iconRes, null, null);
        return this;
    }
    
    /**
     * Sets the header's view. This replaces the title and icon. Called by the
     * builder-style methods of subclasses.
     * 
     * @param view The new view.
     * @return This MenuBuilder so additional setters can be called.
     */
    protected MenuBuilder setHeaderViewInt(View view) {
        setHeaderInternal(0, null, 0, null, view);
        return this;
    }
    
    public CharSequence getHeaderTitle() {
        return mHeaderTitle;
    }
    
    public Drawable getHeaderIcon() {
        return mHeaderIcon;
    }
    
    public View getHeaderView() {
        return mHeaderView;
    }
    
    /**
     * Gets the root menu (if this is a submenu, find its root menu).
     * @return The root menu.
     */
    public MenuBuilder getRootMenu() {
        return this;
    }
    
    /**
     * Sets the current menu info that is set on all items added to this menu
     * (until this is called again with different menu info, in which case that
     * one will be added to all subsequent item additions).
     * 
     * @param menuInfo The extra menu information to add.
     */
    public void setCurrentMenuInfo(ContextMenuInfo menuInfo) {
        mCurrentMenuInfo = menuInfo;
    }

    /**
     * Gets an adapter for providing items and their views.
     * 
     * @param menuType The type of menu to get an adapter for.
     * @return A {@link MenuAdapter} for this menu with the given menu type.
     */
    public MenuAdapter getMenuAdapter(int menuType) {
        return new MenuAdapter(menuType);
    }

    void setOptionalIconsVisible(boolean visible) {
        mOptionalIconsVisible = visible;
    }
    
    boolean getOptionalIconsVisible() {
        return mOptionalIconsVisible;
    }

    public void saveHierarchyState(Bundle outState) {
        SparseArray<Parcelable> viewStates = new SparseArray<Parcelable>();
        
        MenuType[] menuTypes = mMenuTypes;
        for (int i = NUM_TYPES - 1; i >= 0; i--) {
            if (menuTypes[i] == null) {
                continue;
            }

            if (menuTypes[i].hasMenuView()) {
                ((View) menuTypes[i].getMenuView(null)).saveHierarchyState(viewStates);
            }
        }
        
        outState.putSparseParcelableArray(VIEWS_TAG, viewStates);
    }

    public void restoreHierarchyState(Bundle inState) {
        // Save this for menu views opened later
        SparseArray<Parcelable> viewStates = mFrozenViewStates = inState
                .getSparseParcelableArray(VIEWS_TAG);
        
        // Thaw those menu views already open
        MenuType[] menuTypes = mMenuTypes;
        for (int i = NUM_TYPES - 1; i >= 0; i--) {
            if (menuTypes[i] == null) {
                continue;
            }
            
            if (menuTypes[i].hasMenuView()) {
                ((View) menuTypes[i].getMenuView(null)).restoreHierarchyState(viewStates);
            }
        }
    }
    
    /**
     * An adapter that allows an {@link AdapterView} to use this {@link MenuBuilder} as a data
     * source.  This adapter will use only the visible/shown items from the menu.
     */
    public class MenuAdapter extends BaseAdapter {
        private int mMenuType;
        
        public MenuAdapter(int menuType) {
            mMenuType = menuType;
        }

        public int getOffset() {
            if (mMenuType == TYPE_EXPANDED) {
                return getNumIconMenuItemsShown(); 
            } else {
                return 0;
            }
        }
        
        public int getCount() {
            return getVisibleItems().size() - getOffset();
        }

        public MenuItemImpl getItem(int position) {
            return getVisibleItems().get(position + getOffset());
        }

        public long getItemId(int position) {
            // Since a menu item's ID is optional, we'll use the position as an
            // ID for the item in the AdapterView
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            return ((MenuItemImpl) getItem(position)).getItemView(mMenuType, parent);
        }

    }
}

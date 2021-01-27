package com.smartdevicelink.managers.screen.menu;

import com.livio.taskmaster.Task;
import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.ISdl;
import com.smartdevicelink.managers.ManagerUtility;
import com.smartdevicelink.managers.file.FileManager;
import com.smartdevicelink.managers.file.MultipleFileCompletionListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.screen.menu.DynamicMenuUpdateAlgorithm.MenuCellState;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.WindowCapability;
import com.smartdevicelink.proxy.rpc.enums.ImageFieldName;
import com.smartdevicelink.proxy.rpc.enums.MenuLayout;
import com.smartdevicelink.proxy.rpc.listeners.OnMultipleRequestListener;
import com.smartdevicelink.util.DebugTool;

import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.smartdevicelink.managers.screen.menu.BaseMenuManager.lastMenuId;
import static com.smartdevicelink.managers.screen.menu.BaseMenuManager.parentIdNotFound;
import static com.smartdevicelink.managers.screen.menu.MenuReplaceUtilities.*;

/**
 * Created by Bilal Alsharifi on 1/20/21.
 */
class MenuReplaceDynamicOperation extends Task {
    private static final String TAG = "MenuReplaceDynamicOperation";

    private final WeakReference<ISdl> internalInterface;
    private final WeakReference<FileManager> fileManager;
    private final WindowCapability windowCapability;
    private List<MenuCell> currentMenu;
    private final List<MenuCell> updatedMenu;
    private List<MenuCell> oldKeeps;
    private List<MenuCell> newKeeps;
    private final MenuManagerCompletionListener operationCompletionListener;
    private MenuConfiguration menuConfiguration;

    MenuReplaceDynamicOperation(ISdl internalInterface, FileManager fileManager, WindowCapability windowCapability, MenuConfiguration menuConfiguration, List<MenuCell> currentMenu, List<MenuCell> updatedMenu, MenuManagerCompletionListener operationCompletionListener) {
        super(TAG);
        this.internalInterface = new WeakReference<>(internalInterface);
        this.fileManager = new WeakReference<>(fileManager);
        this.windowCapability = windowCapability;
        this.menuConfiguration = menuConfiguration;
        this.currentMenu = currentMenu;
        this.updatedMenu = updatedMenu;
        this.operationCompletionListener = operationCompletionListener;
    }

    @Override
    public void onExecute() {
        start();
    }

    private void start() {
        if (getState() == Task.CANCELED) {
            return;
        }

        updateMenuCells(new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                finishOperation(success);
            }
        });
    }
    
    private void updateMenuCells(final CompletionListener listener) {
        // Upload the Artworks
        List<SdlArtwork> artworksToBeUploaded = findAllArtworksToBeUploadedFromCells(updatedMenu, fileManager.get(), windowCapability);
        if (!artworksToBeUploaded.isEmpty() && fileManager.get() != null) {
            fileManager.get().uploadArtworks(artworksToBeUploaded, new MultipleFileCompletionListener() {
                @Override
                public void onComplete(Map<String, String> errors) {
                    if (errors != null && !errors.isEmpty()) {
                        DebugTool.logError(TAG, "Error uploading Menu Artworks: " + errors.toString());
                    } else {
                        DebugTool.logInfo(TAG, "Menu Artworks Uploaded");
                    }
                    // proceed
                    updateMenuAndDetermineBestUpdateMethod(listener);
                }
            });
        } else {
            // No Artworks to be uploaded, send off
            updateMenuAndDetermineBestUpdateMethod(listener);
        }
    }

    private void updateMenuAndDetermineBestUpdateMethod(CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        // Run the lists through the new algorithm
        DynamicMenuUpdateRunScore rootScore = DynamicMenuUpdateAlgorithm.compareOldMenuCells(currentMenu, updatedMenu);
        if (rootScore == null) {
            // Send initial menu without dynamic updates because oldMenuCells is null
            DebugTool.logInfo(TAG, "Creating initial Menu");
            updateIdsOnMenuCells(updatedMenu, parentIdNotFound);
            createAndSendEntireMenu(listener);
        } else {
            DebugTool.logInfo(TAG, "Dynamically Updating Menu");
            if (updatedMenu.isEmpty() && (currentMenu != null && !currentMenu.isEmpty())) {
                // the dev wants to clear the menu. We have old cells and an empty array of new ones.
                deleteMenuWhenNewCellsEmpty(listener);
            } else {
                // lets dynamically update the root menu
                dynamicallyUpdateRootMenu(rootScore, listener);
            }
        }
    }

    private void deleteMenuWhenNewCellsEmpty(final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        sendDeleteRPCs(deleteCommandsForCells(currentMenu), new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                if (!success) {
                    DebugTool.logError(TAG, "Error Sending Current Menu");
                } else {
                    DebugTool.logInfo(TAG, "Successfully Cleared Menu");
                }
                currentMenu = null;
                listener.onComplete(success);
            }
        });
    }

    private List<MenuCell> filterDeleteMenuItemsWithOldMenuItems(List<MenuCell> oldMenuCells, List<MenuCellState> oldStatusList){
        List<MenuCell> deleteCells = new ArrayList<>();
        // The index of the status should correlate 1-1 with the number of items in the menu
        // [KEEP,DELETE,KEEP,DELETE] => [A,B,C,D] = [B,D]
        for (int index = 0; index < oldStatusList.size(); index++) {
            if (oldStatusList.get(index).equals(MenuCellState.DELETE)) {
                deleteCells.add(oldMenuCells.get(index));
            }
        }
        return deleteCells;
    }

    private List<MenuCell> filterAddMenuItemsWithNewMenuItems(List<MenuCell> newMenuCells, List<MenuCellState> newStatusList){
        List<MenuCell> addCells = new ArrayList<>();
        // The index of the status should correlate 1-1 with the number of items in the menu
        // [KEEP,ADD,KEEP,ADD] => [A,B,C,D] = [B,D]
        for (int index = 0; index < newStatusList.size(); index++) {
            if (newStatusList.get(index).equals(MenuCellState.ADD)) {
                addCells.add(newMenuCells.get(index));
            }
        }
        return addCells;
    }

    private List<MenuCell> filterKeepMenuItemsWithOldMenuItems(List<MenuCell> oldMenuCells, List<MenuCellState> keepStatusList){
        List<MenuCell> keepMenuCells = new ArrayList<>();
        for (int index = 0; index < keepStatusList.size(); index++) {
            if (keepStatusList.get(index).equals(MenuCellState.KEEP)) {
                keepMenuCells.add(oldMenuCells.get(index));
            }
        }
        return keepMenuCells;
    }

    private List<MenuCell> filterKeepMenuItemsWithNewMenuItems(List<MenuCell> newMenuCells, List<MenuCellState> keepStatusList){
        List<MenuCell> keepMenuCells = new ArrayList<>();
        for (int index = 0; index < keepStatusList.size(); index++) {
            if (keepStatusList.get(index).equals(MenuCellState.KEEP)) {
                keepMenuCells.add(newMenuCells.get(index));
            }
        }
        return keepMenuCells;
    }

    private void dynamicallyUpdateRootMenu(DynamicMenuUpdateRunScore bestRootScore, CompletionListener listener) {
        // We need to run through the keeps and see if they have subCells, as they also need to be run through the compare function.
        List<MenuCellState> deleteMenuStatus = bestRootScore.getOldStatus();
        List<MenuCellState> addMenuStatus = bestRootScore.getUpdatedStatus();
        List<RPCRequest> deleteCommands;

        // Set up deletes
        List<MenuCell> cellsToDelete = filterDeleteMenuItemsWithOldMenuItems(currentMenu, deleteMenuStatus);
        oldKeeps = filterKeepMenuItemsWithOldMenuItems(currentMenu, deleteMenuStatus);

        // create the delete commands
        deleteCommands = deleteCommandsForCells(cellsToDelete);

        // Set up the adds
        List<MenuCell> cellsToAdd = filterAddMenuItemsWithNewMenuItems(updatedMenu, addMenuStatus);
        newKeeps = filterKeepMenuItemsWithNewMenuItems(updatedMenu, addMenuStatus);

        updateIdsOnDynamicCells(cellsToAdd);
        // this is needed for the onCommands to still work
        transferIdsToKeptCells(newKeeps);

        if (!cellsToAdd.isEmpty()) { // todo what if adds was empty but deletes was not?
            DebugTool.logInfo(TAG, "Sending root menu updates");
            sendDynamicRootMenuRPCs(deleteCommands, cellsToAdd, listener);
        } else {
            DebugTool.logInfo(TAG, "All root menu items are kept. Check the sub menus");
            runSubMenuCompareAlgorithm(listener);
        }
    }

    private void createAndSendEntireMenu(final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        deleteRootMenu(new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                createAndSendMenuCellRPCs(updatedMenu, new CompletionListener() {
                    @Override
                    public void onComplete(boolean success) {
                        if (!success) {
                            DebugTool.logError(TAG, "Error Sending Current Menu");
                        }

                        listener.onComplete(success);
                    }
                });
            }
        });
    }

    private void createAndSendMenuCellRPCs(final List<MenuCell> menu, final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        if (menu == null || menu.isEmpty()) {
            if (listener != null) {
                // This can be considered a success if the user was clearing out their menu
                listener.onComplete(true);
            }
            return;
        }

        MenuLayout defaultSubmenuLayout = menuConfiguration != null ? menuConfiguration.getSubMenuLayout() : null;

        List<RPCRequest> mainMenuCommands = mainMenuCommandsForCells(menu, fileManager.get(), windowCapability, updatedMenu, defaultSubmenuLayout);
        final List<RPCRequest> subMenuCommands = subMenuCommandsForCells(menu, fileManager.get(), windowCapability, defaultSubmenuLayout);


        internalInterface.get().sendRPCs(mainMenuCommands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {
            }

            @Override
            public void onFinished() {
                if (!subMenuCommands.isEmpty()) {
                    DebugTool.logInfo(TAG, "Finished sending main menu commands. Sending sub menu commands.");
                    sendSubMenuCommandRPCs(subMenuCommands, listener);
                } else {
                    if (newKeeps != null && !newKeeps.isEmpty()) {
                        runSubMenuCompareAlgorithm(listener);
                    } else {
                        DebugTool.logInfo(TAG, "Finished sending main menu commands.");

                        if (listener != null) {
                            listener.onComplete(true);
                        }
                    }
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    try {
                        DebugTool.logInfo(TAG, "Main Menu response: " + response.serializeJSON().toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    DebugTool.logError(TAG, "Result: " + response.getResultCode() + " Info: " + response.getInfo());
                }
            }
        });
    }

    private void sendSubMenuCommandRPCs(List<RPCRequest> commands, final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        internalInterface.get().sendRPCs(commands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {
            }

            @Override
            public void onFinished() {
                if (newKeeps != null && !newKeeps.isEmpty()) {
                    runSubMenuCompareAlgorithm(listener);
                } else {
                    DebugTool.logInfo(TAG, "Finished Updating Menu");

                    if (listener != null) {
                        listener.onComplete(true);
                    }
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    try {
                        DebugTool.logInfo(TAG, "Sub Menu response: " + response.serializeJSON().toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    DebugTool.logError(TAG, "Failed to send sub menu commands: " + response.getInfo());
                }
            }
        });
    }

    private void createAndSendDynamicSubMenuRPCs(List<MenuCell> newMenu, final List<MenuCell> adds, final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        if (adds.isEmpty()) {
            if (listener != null) {
                // This can be considered a success if the user was clearing out their menu
                DebugTool.logError(TAG, "Called createAndSendDynamicSubMenuRPCs with empty menu");
                listener.onComplete(true);
            }
            return;
        }

        List<RPCRequest> mainMenuCommands;

        if (!shouldRPCsIncludeImages(adds, fileManager.get()) || !supportsImages(windowCapability)) {
            // Send artwork-less menu
            mainMenuCommands = createCommandsForDynamicSubCells(newMenu, adds, false);
        } else {
            mainMenuCommands = createCommandsForDynamicSubCells(newMenu, adds, true);
        }

        internalInterface.get().sendRPCs(mainMenuCommands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {
                // nothing here
            }

            @Override
            public void onFinished() {
                if (listener != null) {
                    listener.onComplete(true);
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    try {
                        DebugTool.logInfo(TAG, "Dynamic Sub Menu response: " + response.serializeJSON().toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    DebugTool.logError(TAG, "Result: " + response.getResultCode() + " Info: " + response.getInfo());
                }
            }
        });
    }

    private void deleteRootMenu(final CompletionListener listener) {
        if (currentMenu == null || currentMenu.isEmpty()) {
            if (listener != null) {
                // technically this method is successful if there's nothing to delete
                DebugTool.logInfo(TAG, "No old cells to delete, returning");
                listener.onComplete(true);
            }
        } else {
            sendDeleteRPCs(deleteCommandsForCells(currentMenu), listener);
        }
    }

    private void sendDeleteRPCs(List<RPCRequest> deleteCommands, final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        if (deleteCommands == null || deleteCommands.isEmpty()) {
            // no dynamic deletes required. return
            if (listener != null) {
                // technically this method is successful if there's nothing to delete
                listener.onComplete(true);
            }
            return;
        }

        internalInterface.get().sendRPCs(deleteCommands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {

            }

            @Override
            public void onFinished() {
                DebugTool.logInfo(TAG, "Successfully deleted cells");
                if (listener != null) {
                    listener.onComplete(true);
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {

            }
        });
    }

    private List<RPCRequest> createCommandsForDynamicSubCells(List<MenuCell> oldMenuCells, List<MenuCell> cells, boolean shouldHaveArtwork) {
        List<RPCRequest> builtCommands = new ArrayList<>();
        for (int z = 0; z < oldMenuCells.size(); z++) {
            MenuCell oldCell = oldMenuCells.get(z);
            for (int i = 0; i < cells.size(); i++) {
                MenuCell cell = cells.get(i);
                if (cell.equals(oldCell)) {
                    builtCommands.add(commandForMenuCell(cell, fileManager.get(), windowCapability, z));
                    break;
                }
            }
        }
        return builtCommands;
    }

    private void sendDynamicRootMenuRPCs(List<RPCRequest> deleteCommands, final List<MenuCell> updatedCells, final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        sendDeleteRPCs(deleteCommands, new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                createAndSendMenuCellRPCs(updatedCells, new CompletionListener() {
                    @Override
                    public void onComplete(boolean success) {
                        if (!success) {
                            DebugTool.logError(TAG, "Error Sending Current Menu");
                        }

                        listener.onComplete(success);
                    }
                });
            }
        });
    }

    private void runSubMenuCompareAlgorithm(CompletionListener listener) {
        // any cells that were re-added have their sub-cells added with them
        // at this point all we care about are the cells that were deemed equal and kept.
        if (newKeeps == null || newKeeps.isEmpty()) {
            listener.onComplete(true);
            return;
        }

        List<SubCellCommandList> commandLists = new ArrayList<>();

        for (int i = 0; i < newKeeps.size(); i++) {
            MenuCell newKeptCell = newKeeps.get(i);
            MenuCell oldKeptCell = oldKeeps.get(i);

            if (oldKeptCell.getSubCells() != null && !oldKeptCell.getSubCells().isEmpty() && newKeptCell.getSubCells() != null && !newKeptCell.getSubCells().isEmpty()) {
                DynamicMenuUpdateRunScore subScore = DynamicMenuUpdateAlgorithm.startCompareAtRun(oldKeptCell.getSubCells(), newKeptCell.getSubCells());

                if (subScore != null) {
                    DebugTool.logInfo(TAG, "Sub menu Run Score: " + oldKeptCell.getTitle() + " Score: " + subScore.getScore());
                    SubCellCommandList commandList = new SubCellCommandList(oldKeptCell.getTitle(), oldKeptCell.getCellId(), subScore, oldKeptCell.getSubCells(), newKeptCell.getSubCells());
                    commandLists.add(commandList);
                }
            }
        }
        createSubMenuDynamicCommands(commandLists, listener);
    }

    private void createSubMenuDynamicCommands(final List<SubCellCommandList> commandLists, final CompletionListener listener) {
        if (getState() == Task.CANCELED) {
            return;
        }

        if (commandLists.isEmpty()) {
            DebugTool.logInfo(TAG, "All menu updates, including sub menus - done.");
            listener.onComplete(true);
            return;
        }

        final SubCellCommandList commandList = commandLists.remove(0);

        DebugTool.logInfo(TAG, "Creating and Sending Dynamic Sub Commands For Root Menu Cell: " + commandList.getMenuTitle());

        // grab the scores
        DynamicMenuUpdateRunScore score = commandList.getListsScore();
        List<MenuCellState> newStates = score.getUpdatedStatus();
        List<MenuCellState> oldStates = score.getOldStatus();

        // Grab the sub-menus from the parent cell
        final List<MenuCell> oldCells = commandList.getOldList();
        final List<MenuCell> newCells = commandList.getNewList();

        List<MenuCell> cellsToDelete = filterDeleteMenuItemsWithOldMenuItems(currentMenu, oldStates);

        // create the delete commands
        List<RPCRequest> deleteCommands = deleteCommandsForCells(cellsToDelete);

        // Set up the adds
        List<MenuCell> cellsToAdd = filterAddMenuItemsWithNewMenuItems(updatedMenu, newStates);
        List<MenuCell> subCellKeepsNew = filterKeepMenuItemsWithNewMenuItems(updatedMenu, newStates);

        final List<MenuCell> addsWithNewIds = updateIdsOnDynamicSubCells(oldCells, cellsToAdd, commandList.getParentId());
        // this is needed for the onCommands to still work
        transferIdsToKeptSubCells(oldCells, subCellKeepsNew);

        sendDeleteRPCs(deleteCommands, new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                if (addsWithNewIds != null && !addsWithNewIds.isEmpty()) {
                    createAndSendDynamicSubMenuRPCs(newCells, addsWithNewIds, new CompletionListener() {
                        @Override
                        public void onComplete(boolean success) {
                            // recurse through next sub list
                            DebugTool.logInfo(TAG, "Finished Sending Dynamic Sub Commands For Root Menu Cell: " + commandList.getMenuTitle());
                            createSubMenuDynamicCommands(commandLists, listener);
                        }
                    });
                } else {
                    // no add commands to send, recurse through next sub list
                    DebugTool.logInfo(TAG, "Finished Sending Dynamic Sub Commands For Root Menu Cell: " + commandList.getMenuTitle());
                    createSubMenuDynamicCommands(commandLists, listener);
                }
            }
        });
    }

    private void updateIdsOnDynamicCells(List<MenuCell> dynamicCells) {
        if (updatedMenu != null && !updatedMenu.isEmpty() && dynamicCells != null && !dynamicCells.isEmpty()) {
            for (int z = 0; z < updatedMenu.size(); z++) {
                MenuCell mainCell = updatedMenu.get(z);
                for (int i = 0; i < dynamicCells.size(); i++) {
                    MenuCell dynamicCell = dynamicCells.get(i);
                    if (mainCell.equals(dynamicCell)) {
                        int newId = ++lastMenuId;
                        updatedMenu.get(z).setCellId(newId);
                        dynamicCells.get(i).setCellId(newId);

                        if (mainCell.getSubCells() != null && !mainCell.getSubCells().isEmpty()) {
                            updateIdsOnMenuCells(mainCell.getSubCells(), mainCell.getCellId());
                        }
                        break;
                    }
                }
            }
        }
    }

    private List<MenuCell> updateIdsOnDynamicSubCells(List<MenuCell> oldList, List<MenuCell> dynamicCells, Integer parentId) {
        if (oldList != null && !oldList.isEmpty() && dynamicCells != null && !dynamicCells.isEmpty()) {
            for (int z = 0; z < oldList.size(); z++) {
                MenuCell mainCell = oldList.get(z);
                for (int i = 0; i < dynamicCells.size(); i++) {
                    MenuCell dynamicCell = dynamicCells.get(i);
                    if (mainCell.equals(dynamicCell)) {
                        int newId = ++lastMenuId;
                        oldList.get(z).setCellId(newId);
                        dynamicCells.get(i).setParentCellId(parentId);
                        dynamicCells.get(i).setCellId(newId);
                    } else {
                        int newId = ++lastMenuId;
                        dynamicCells.get(i).setParentCellId(parentId);
                        dynamicCells.get(i).setCellId(newId);
                    }
                }
            }
            return dynamicCells;
        }
        return null;
    }

    private void updateIdsOnMenuCells(List<MenuCell> cells, int parentId) {
        for (MenuCell cell : cells) {
            int newId = ++lastMenuId;
            cell.setCellId(newId);
            cell.setParentCellId(parentId);
            if (cell.getSubCells() != null && !cell.getSubCells().isEmpty()) {
                updateIdsOnMenuCells(cell.getSubCells(), cell.getCellId());
            }
        }
    }

    private void transferIdsToKeptCells(List<MenuCell> keeps) {
        for (int z = 0; z < currentMenu.size(); z++) {
            MenuCell oldCell = currentMenu.get(z);
            for (int i = 0; i < keeps.size(); i++) {
                MenuCell keptCell = keeps.get(i);
                if (oldCell.equals(keptCell)) {
                    keptCell.setCellId(oldCell.getCellId());
                    break;
                }
            }
        }
    }

    private void transferIdsToKeptSubCells(List<MenuCell> old, List<MenuCell> keeps) {
        for (int z = 0; z < old.size(); z++) {
            MenuCell oldCell = old.get(z);
            for (int i = 0; i < keeps.size(); i++) {
                MenuCell keptCell = keeps.get(i);
                if (oldCell.equals(keptCell)) {
                    keptCell.setCellId(oldCell.getCellId());
                    break;
                }
            }
        }
    }

    private boolean shouldRPCsIncludeImages(List<MenuCell> cells, FileManager fileManager) {
        for (MenuCell cell : cells) {
            SdlArtwork artwork = cell.getIcon();
            if (artwork != null && !artwork.isStaticIcon() && fileManager != null && !fileManager.hasUploadedFile(artwork)) {
                return false;
            } else if (cell.getSubCells() != null && !cell.getSubCells().isEmpty()) {
                return shouldRPCsIncludeImages(cell.getSubCells(), fileManager);
            }
        }
        return true;
    }

    private boolean supportsImages(WindowCapability windowCapability) {
        return windowCapability == null || ManagerUtility.WindowCapabilityUtility.hasImageFieldOfName(windowCapability, ImageFieldName.cmdIcon);
    }

    void setMenuConfiguration(MenuConfiguration menuConfiguration) {
        this.menuConfiguration = menuConfiguration;
    }

    public void setCurrentMenu(List<MenuCell> currentMenuCells) {
        this.currentMenu = currentMenuCells;
    }

    private void finishOperation(boolean success) {
        if (operationCompletionListener != null) {
            operationCompletionListener.onComplete(success, currentMenu);
        }
        onFinished();
    }
}

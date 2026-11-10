from pathlib import Path
root = Path(__file__).resolve().parents[1]
backup = (root/'android-direct/src/pro/sitescope/app/BackupManager.java').read_text()
settings = (root/'android-direct/src/pro/sitescope/app/SettingsActivity.java').read_text()
models = (root/'android-direct/src/pro/sitescope/app/Models.java').read_text()
store = (root/'android-direct/src/pro/sitescope/app/DataStore.java').read_text()
manifest = (root/'android-direct/AndroidManifest.xml').read_text()
assert 'SiteScopeProBackup' in backup
assert 'SCHEMA_VERSION = 6' in backup
assert 'AppDatabase.get(context).exportPortable()' in backup
assert 'restorePortable' in backup
for pref in ['sitescope_data_v1', 'sitescope_iptv', 'ip_intelligence', 'connection_profiles', 'protocol_router_preferences', 'ConnectionSessionStore.PREFS', 'DeviceInventoryStore.PREFS']:
    assert pref in backup
assert 'SettingsStore.PREFS' in backup
assert 'sitescope_secure_v1' not in backup
for token in ['ACTION_CREATE_DOCUMENT', 'ACTION_OPEN_DOCUMENT', 'BackupManager.restore', 'BackupManager.exportTo']:
    assert token in settings
for token in ['notes', 'tags', 'pinned', 'archived', 'userNamed']:
    assert token in models
for token in ['updateProjectMetadata', 'setProjectPinned', 'setProjectArchived']:
    assert token in store
assert 'android:name=".SettingsActivity"' in manifest
assert 'android:name=".ConnectionsActivity"' in manifest
assert 'android:name=".InventoryActivity"' in manifest
print('BACKUP_PROJECTS_CONTRACT_PASS')

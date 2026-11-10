from pathlib import Path
root=Path(__file__).resolve().parents[1]
app=root/'android-direct/src/pro/sitescope/app'
profiles=(app/'ConnectionProfileStore.java').read_text()
sessions=(app/'ConnectionSessionStore.java').read_text()
probe=(app/'EndpointProbe.java').read_text()
connections=(app/'ConnectionsActivity.java').read_text()
inventory=(app/'DeviceInventoryStore.java').read_text()
inventory_ui=(app/'InventoryActivity.java').read_text()
main=(app/'MainActivity.java').read_text()
discovery=(app/'DiscoveryActivity.java').read_text()
hub=(app/'IpEngineeringActivity.java').read_text()
router=(app/'ProtocolRouter.java').read_text()
for token in ['notes', 'tags', 'pinned', 'lastCheckedAt', 'lastStatus', 'lastLatencyMs', 'launchCount']:
    assert token in profiles
for token in ['recordOpen', 'recordHealth', 'events_json']:
    assert token in sessions
for token in ['HttpURLConnection', 'Socket', 'defaultPort']:
    assert token in probe
for token in ['✓ все', 'checkAllProfiles', 'Журнал', 'editProfile']:
    assert token in connections
for token in ['saveSnapshot', 'aggregate', 'setAlias', 'ipSet']:
    assert token in inventory
for token in ['Инвентаризация LAN', 'Новые', 'Исчезли', 'openServices']:
    assert token in inventory_ui
assert 'DeviceInventoryStore.saveSnapshot' in discovery
assert 'InventoryActivity.class' in hub
assert 'ConnectionSessionStore.recordOpen' in router
print('CONNECTION_INVENTORY_CONTRACT_PASS')

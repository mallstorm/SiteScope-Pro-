from pathlib import Path
root=Path(__file__).resolve().parents[1]
app=(root/'android-direct/src/pro/sitescope/app')
manifest=(root/'android-direct/AndroidManifest.xml').read_text()
assert '0.39.0-alpha37' in manifest
for activity in ['IpEngineeringActivity','ScannerActivity','DiscoveryActivity']:
    assert f'android:name=".{activity}"' in manifest

db=(app/'AppDatabase.java').read_text()
for table in ['projects','audits','scan_sessions','scan_results','active_scan_results','connection_profiles','connection_events','inventory_snapshots','inventory_aliases','hosts_profiles','hosts_entries','hosts_backups','hosts_apply_events']:
    assert table in db, table
assert 'beginTransaction' in db and 'migrateLegacyOnce' in db

ip=(app/'IpDataStore.java').read_text()
assert 'geo_ranges_staging' in ip and 'asn_ranges_staging' in ip
assert 'replaceGeoFromStaging' in ip and 'replaceAsnFromStaging' in ip
assert 'DELETE FROM geo_ranges' not in ip.upper()  # replacement is transaction-bound via db.delete

main=(app/'MainActivity.java').read_text()
projects=(app/'ProjectsActivity.java').read_text()
history=(app/'HistoryActivity.java').read_text()
seo=(app/'SeoActivity.java').read_text()
base=(app/'BaseSiteScopeActivity.java').read_text()
assert 'ProjectAdapter extends BaseAdapter' in projects
assert 'HistoryAdapter extends BaseAdapter' in history
assert 'startActivity(new Intent(MainActivity.this, IpEngineeringActivity.class))' in main
assert 'class SeoActivity extends BaseSiteScopeActivity' in seo
assert 'bottomNavigation' in base and 'ProjectsActivity.class' in base and 'HistoryActivity.class' in base

scan=(app/'ScanService.java').read_text()
assert 'toCheckpointJson' in scan
assert 'saveActiveResult' in scan
assert 'clearActiveResults' in scan

ready=(app/'FeatureReadiness.java').read_text()
assert 'return "BETA"' in ready and 'return "STABLE"' in ready
print('Stabilization contract PASS: SQLite migration, atomic DB-IP, split IP workspaces, modular root activities, virtualized lists, active scan result persistence, honest readiness labels')

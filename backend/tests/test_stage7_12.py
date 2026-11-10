import pytest
from app.scanner_policy import expand_targets
from app.monitoring import make_snapshot,compare
from app.reports import audit_to_markdown,scan_to_csv
from app.ai_assistant import EvidenceBoundAssistant

def test_authorization_required():
    with pytest.raises(ValueError): expand_targets('192.0.2.1',False)
def test_expand_small_cidr(): assert expand_targets('192.0.2.0/30',True)==['192.0.2.1','192.0.2.2']
def test_monitor_compare(): assert compare({'a':1},{'a':2})[0]['field']=='a'
def test_reports():
    assert 'Оценка' in audit_to_markdown({'target':'x','score':90,'issues':[]})
    assert 'host,port' in scan_to_csv([{'host':'x','port':80,'open':True}])
def test_ai_evidence_bound():
    plan=EvidenceBoundAssistant().build_plan({'issues':[{'severity':'high','title':'X','recommendation':'Y','code':'R'}]})
    assert plan['tasks'][0]['source_rule']=='R'


def test_extended_target_masks_and_port_expressions():
    from app.scanner_policy import expand_targets, parse_ports_expression, validate_scan_budget
    assert expand_targets('192.168.1.0/30', True) == ['192.168.1.1', '192.168.1.2']
    assert len(expand_targets('192.168.1.0/255.255.255.0', True)) == 254
    assert expand_targets('192.168.1.10-12', True) == ['192.168.1.10', '192.168.1.11', '192.168.1.12']
    assert len(expand_targets('192.168.1.*', True)) == 254
    assert parse_ports_expression('22,80,440-443,1-3') == [1, 2, 3, 22, 80, 440, 441, 442, 443]
    assert validate_scan_budget(['a'] * 10, list(range(1, 801))) == 8000


def test_extended_private_profile_and_public_guard():
    from app.scanner_policy import expand_targets, parse_ports_expression, validate_scan_budget
    private_hosts = expand_targets('192.168.0.0/16', True, extended_private=True)
    assert len(private_hosts) == 65534
    assert len(parse_ports_expression('1-65535', extended_private=True)) == 65535
    assert validate_scan_budget(private_hosts[:100], list(range(1, 1001)), extended_private=True) == 100000
    with pytest.raises(ValueError):
        validate_scan_budget(['192.0.2.1'] * 5000, [80], extended_private=True)

import pytest
from app.security import validate_public_url, UnsafeTarget

def test_localhost_blocked():
    with pytest.raises(UnsafeTarget): validate_public_url("http://localhost/")

def test_loopback_blocked():
    with pytest.raises(UnsafeTarget): validate_public_url("http://127.0.0.1/")

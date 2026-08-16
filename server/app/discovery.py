import logging
import socket

from zeroconf import Zeroconf, ServiceInfo

from app.config import PORT, ZEROCONF_SERVICE_NAME, ZEROCONF_SERVICE_TYPE

logger = logging.getLogger(__name__)


def _local_ip() -> str:
    """Best-effort LAN IP: open a UDP socket toward a public address
    (no packet is actually sent) to let the OS pick the outbound
    interface, then read its local address."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class ServerAdvertiser:
    """Registers the work-log-app server on mDNS (_worklogapp._tcp.local.)
    so the Android client's NsdManager can discover it on the LAN."""

    def __init__(self) -> None:
        self._zeroconf: Zeroconf | None = None
        self._info: ServiceInfo | None = None

    def start(self) -> None:
        ip = _local_ip()
        self._info = ServiceInfo(
            ZEROCONF_SERVICE_TYPE,
            ZEROCONF_SERVICE_NAME,
            addresses=[socket.inet_aton(ip)],
            port=PORT,
            properties={},
        )
        self._zeroconf = Zeroconf()
        try:
            # allow_name_change: if a previous crash left a stale
            # announcement of this same name on the network (its TTL not
            # yet expired), auto-rename instead of raising
            # NonUniqueNameException. The Android client matches on
            # service *type*, not the exact instance name, so a renamed
            # instance is still discoverable.
            self._zeroconf.register_service(self._info, allow_name_change=True)
        except Exception:
            self._zeroconf.close()
            self._zeroconf = None
            self._info = None
            raise
        logger.info("mDNS advertised: %s at %s:%s", ZEROCONF_SERVICE_NAME, ip, PORT)

    def stop(self) -> None:
        if self._zeroconf and self._info:
            self._zeroconf.unregister_service(self._info)
            self._zeroconf.close()

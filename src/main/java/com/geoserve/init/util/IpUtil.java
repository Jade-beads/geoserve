package com.geoserve.init.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 本机 IP 工具。
 *
 * 后续集成到业务服务时，可替换为业务工程已有的 IpUtil。
 */
public final class IpUtil {

    private IpUtil() {
    }

    public static InetAddress getHostIp() throws SocketException {
        Enumeration<NetworkInterface> allNetInterfaces = NetworkInterface.getNetworkInterfaces();
        while (allNetInterfaces.hasMoreElements()) {
            NetworkInterface netInterface = allNetInterfaces.nextElement();
            Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress ip = addresses.nextElement();
                if (ip != null
                        && ip instanceof Inet4Address
                        && !ip.isLoopbackAddress()
                        && ip.getHostAddress().indexOf(":") == -1) {
                    return ip;
                }
            }
        }
        throw new RuntimeException("no-available-ip");
    }
}

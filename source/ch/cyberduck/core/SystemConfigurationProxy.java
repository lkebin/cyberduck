package ch.cyberduck.core;

/*
 *  Copyright (c) 2005 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import ch.cyberduck.core.library.Native;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.net.URI;

/**
 * @version $Id$
 */
public final class SystemConfigurationProxy extends AbstractProxyFinder implements ProxyFinder {
    private static final Logger log = Logger.getLogger(SystemConfigurationProxy.class);

    public static void register() {
        ProxyFactory.addFactory(Factory.NATIVE_PLATFORM, new Factory());
    }

    private static class Factory extends ProxyFactory {
        @Override
        protected ProxyFinder create() {
            return new SystemConfigurationProxy();
        }
    }

    static {
        Native.load("Proxy");
    }

    private HostUrlProvider provider
            = new HostUrlProvider();

    protected SystemConfigurationProxy() {
        //
    }

    /**
     * Use passive connect mode
     *
     * @return True if enabled in the system preferences
     */
    public native boolean usePassiveFTPNative();

    @Override
    public boolean usePassiveFTP() {
        return this.usePassiveFTPNative();
    }

    @Override
    public Proxy find(final Host target) {
        final String route = this.findNative(provider.get(target));
        if(null == route) {
            if(log.isInfoEnabled()) {
                log.info(String.format("No poxy configuration found for target %s", target));
            }
            // Direct
            return Proxy.DIRECT;
        }
        final URI proxy = URI.create(route);
        try {
            return new Proxy(Proxy.Type.valueOf(StringUtils.upperCase(proxy.getScheme())),
                    proxy.getHost(), proxy.getPort());
        }
        catch(IllegalArgumentException e) {
            log.warn(String.format("Unsupported scheme for proxy %s", proxy));
        }
        return Proxy.DIRECT;
    }

    /**
     * Find SOCKS and HTTP proxy settings
     *
     * @param target The URL the application intends to access
     * @return Proxy URL or null if direct connection
     */
    public native String findNative(String target);
}

/*
 *     Briar Mailbox
 *     Copyright (C) 2021-2022  The Briar Project
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.briarproject.mailbox.core.tor;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;

import org.briarproject.mailbox.core.event.EventBus;
import org.briarproject.mailbox.core.event.EventExecutor;
import org.briarproject.mailbox.core.lifecycle.Service;
import org.briarproject.mailbox.core.system.TaskScheduler;
import org.briarproject.mailbox.core.system.TaskScheduler.Cancellable;
import org.slf4j.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.content.Intent.ACTION_SCREEN_ON;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED;
import static java.net.NetworkInterface.getNetworkInterfaces;
import static java.util.Collections.list;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.mailbox.core.util.LogUtils.info;
import static org.briarproject.mailbox.core.util.LogUtils.logException;
import static org.slf4j.LoggerFactory.getLogger;

public class AndroidNetworkManager implements NetworkManager, Service {

	private static final Logger LOG = getLogger(AndroidNetworkManager.class);

	// See android.net.wifi.WifiManager
	private static final String WIFI_AP_STATE_CHANGED_ACTION =
			"android.net.wifi.WIFI_AP_STATE_CHANGED";

	private final TaskScheduler scheduler;
	private final EventBus eventBus;
	private final Executor eventExecutor;
	private final Application app;
	private final ConnectivityManager connectivityManager;
	private final AtomicReference<Cancellable> connectivityCheck =
			new AtomicReference<>();
	private final AtomicBoolean used = new AtomicBoolean(false);

	private volatile BroadcastReceiver networkStateReceiver = null;

	@Inject
	AndroidNetworkManager(TaskScheduler scheduler, EventBus eventBus,
			@EventExecutor Executor eventExecutor, Application app) {
		this.scheduler = scheduler;
		this.eventBus = eventBus;
		this.eventExecutor = eventExecutor;
		this.app = app;
		connectivityManager = (ConnectivityManager)
				requireNonNull(app.getSystemService(CONNECTIVITY_SERVICE));
	}

	@Override
	public void startService() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		// Register to receive network status events
		networkStateReceiver = new NetworkStateReceiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction(CONNECTIVITY_ACTION);
		filter.addAction(ACTION_SCREEN_ON);
		filter.addAction(ACTION_SCREEN_OFF);
		filter.addAction(WIFI_AP_STATE_CHANGED_ACTION);
		filter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
		if (SDK_INT >= 23) filter.addAction(ACTION_DEVICE_IDLE_MODE_CHANGED);
		app.registerReceiver(networkStateReceiver, filter);
	}

	@Override
	public void stopService() {
		if (networkStateReceiver != null)
			app.unregisterReceiver(networkStateReceiver);
	}

	@Override
	public NetworkStatus getNetworkStatus() {
		NetworkInfo net = connectivityManager.getActiveNetworkInfo();
		// Research into Android's behavior to check network connectivity
		// (https://code.briarproject.org/briar/public-mesh-research/-/issues/19)
		// has shown that NetworkInfo#isConnected() returns true if the device
		// is connected to any Wifi, independent of whether any specific IP
		// address can be reached using it or any domain names can be resolved.
		boolean connected = net != null && net.isConnected();
		boolean wifi = false, ipv6Only = false;
		if (connected) {
			wifi = net.getType() == TYPE_WIFI;
			if (SDK_INT >= 23) ipv6Only = isActiveNetworkIpv6Only();
			else ipv6Only = areAllAvailableNetworksIpv6Only();
		}
		return new NetworkStatus(connected, wifi, ipv6Only);
	}

	/**
	 * Returns true if the
	 * {@link ConnectivityManager#getActiveNetwork() active network} has an
	 * IPv6 unicast address and no IPv4 addresses. The active network is
	 * assumed not to be a loopback interface.
	 */
	@TargetApi(23)
	private boolean isActiveNetworkIpv6Only() {
		Network net = connectivityManager.getActiveNetwork();
		if (net == null) {
			LOG.info("No active network");
			return false;
		}
		LinkProperties props = connectivityManager.getLinkProperties(net);
		if (props == null) {
			LOG.info("No link properties for active network");
			return false;
		}
		boolean hasIpv6Unicast = false;
		for (LinkAddress linkAddress : props.getLinkAddresses()) {
			InetAddress addr = linkAddress.getAddress();
			if (addr instanceof Inet4Address) return false;
			if (!addr.isMulticastAddress()) hasIpv6Unicast = true;
		}
		return hasIpv6Unicast;
	}

	/**
	 * Returns true if the device has at least one network interface with an
	 * IPv6 unicast address and no interfaces with IPv4 addresses, excluding
	 * loopback interfaces and interfaces that are
	 * {@link NetworkInterface#isUp() down}. If this method returns true and
	 * the device has internet access then it's via IPv6 only.
	 */
	private boolean areAllAvailableNetworksIpv6Only() {
		try {
			Enumeration<NetworkInterface> interfaces = getNetworkInterfaces();
			if (interfaces == null) {
				LOG.info("No network interfaces");
				return false;
			}
			boolean hasIpv6Unicast = false;
			for (NetworkInterface i : list(interfaces)) {
				if (i.isLoopback() || !i.isUp()) continue;
				for (InetAddress addr : list(i.getInetAddresses())) {
					if (addr instanceof Inet4Address) return false;
					if (!addr.isMulticastAddress()) hasIpv6Unicast = true;
				}
			}
			return hasIpv6Unicast;
		} catch (SocketException e) {
			logException(LOG, e, "Error while checking for IPv6 only status");
			return false;
		}
	}

	private void updateConnectionStatus() {
		eventBus.broadcast(new NetworkStatusEvent(getNetworkStatus()));
	}

	private void scheduleConnectionStatusUpdate(int delay, TimeUnit unit) {
		Cancellable newConnectivityCheck =
				scheduler.schedule(this::updateConnectionStatus, eventExecutor,
						delay, unit);
		Cancellable oldConnectivityCheck =
				connectivityCheck.getAndSet(newConnectivityCheck);
		if (oldConnectivityCheck != null) oldConnectivityCheck.cancel();
	}

	private class NetworkStateReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context ctx, Intent i) {
			String action = i.getAction();
			info(LOG, () -> "Received broadcast " + action);
			updateConnectionStatus();
			if (isSleepOrDozeEvent(action)) {
				// Allow time for the network to be enabled or disabled
				scheduleConnectionStatusUpdate(1, MINUTES);
			} else if (isApEvent(action)) {
				// The state change may be broadcast before the AP address is
				// visible, so delay handling the event
				scheduleConnectionStatusUpdate(5, SECONDS);
			}
		}

		private boolean isSleepOrDozeEvent(@Nullable String action) {
			boolean isSleep = ACTION_SCREEN_ON.equals(action) ||
					ACTION_SCREEN_OFF.equals(action);
			boolean isDoze = SDK_INT >= 23 &&
					ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action);
			return isSleep || isDoze;
		}

		private boolean isApEvent(@Nullable String action) {
			return WIFI_AP_STATE_CHANGED_ACTION.equals(action) ||
					WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action);
		}
	}
}

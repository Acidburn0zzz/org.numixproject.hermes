package org.numixproject.hermes;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.FragmentManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.sothree.slidinguppanel.SlidingUpPanelLayout;

import org.numixproject.hermes.adapter.ServerListAdapter;
import org.numixproject.hermes.irc.IRCBinder;
import org.numixproject.hermes.activity.AboutActivity;
import org.numixproject.hermes.activity.AddServerActivity;
import org.numixproject.hermes.activity.SettingsActivity;
import org.numixproject.hermes.irc.IRCService;
import org.numixproject.hermes.listener.ServerListener;
import org.numixproject.hermes.model.Broadcast;
import org.numixproject.hermes.model.Server;
import org.numixproject.hermes.model.Status;
import org.numixproject.hermes.receiver.ServerReceiver;

import java.util.ArrayList;

import it.neokree.materialnavigationdrawer.MaterialNavigationDrawer;
import it.neokree.materialnavigationdrawer.elements.MaterialSection;
import it.neokree.materialnavigationdrawer.elements.listeners.MaterialSectionListener;

public class MainActivity extends MaterialNavigationDrawer implements ServiceConnection, ServerListener {
    private static int instanceCount = 0;
    private IRCBinder binder;
    private ServerReceiver receiver;
    private ServerListAdapter adapter;




    @Override
    public void init(Bundle savedInstanceState) {

        MaterialSection home = newSection("Chat", new HomeFragment());
        MaterialSection addserver = newSection("Add new server", new Intent(this, AddServerActivity.class));
        MaterialSection notifications = newSection("Snooze Notifications", new Intent(this, SettingsActivity.class));
        MaterialSection pro = newSection("Upgrade to Pro",  new Intent(this, SettingsActivity.class));
        MaterialSection settings = newSection("Settings", R.drawable.ic_ic_settings_24px , new Intent(this, SettingsActivity.class));
        MaterialSection help = newSection("Help", R.drawable.ic_ic_help_24px , new Intent(this, SettingsActivity.class));
        MaterialSection about = newSection("About", R.drawable.ic_ic_info_24px , new Intent(this, AboutActivity.class));

        addSection(home);
        final FragmentManager fm = getSupportFragmentManager();
        final HomeFragment fragment = (HomeFragment)home.getTargetFragment();

        this.addSubheader("Servers");


        this.addSection(newSection("Connect to ...", new MaterialSectionListener() {
            @Override
            public void onClick(MaterialSection section) {
                fragment.openServerPane();
            }
        }));


        addSection(addserver);


        this.addSection(newSection("Disconnect all", new MaterialSectionListener() {
            @Override
            public void onClick(MaterialSection section) {
                ArrayList<Server> mServers = Hermes.getInstance().getServersAsArrayList();
                for (Server server : mServers) {
                    if (binder.getService().hasConnection(server.getId())) {
                        server.setStatus(Status.DISCONNECTED);
                        server.setMayReconnect(false);
                        binder.getService().getConnection(server.getId()).quitServer();
                    }
                }
                // ugly
                binder.getService().stopForegroundCompat(R.string.app_name);            }
        }));
        addBottomSection(notifications);
        addBottomSection(pro);
        addBottomSection(settings);
        addBottomSection(help);
        addBottomSection(about);
        setDrawerHeaderImage(R.drawable.cover);
        allowArrowAnimation();



             /*
         * With activity:launchMode = standard, we get duplicated activities
         * depending on the task the app was started in. In order to avoid
         * stacking up of this duplicated activities we keep a count of this
         * root activity and let it finish if it already exists
         *
         * Launching the app via the notification icon creates a new task,
         * and there doesn't seem to be a way around this so this is needed
         */

        if (instanceCount > 0) {
            finish();
        }
        instanceCount++;
    }

    /**
     * On pause
     */
    @Override
    public void onPause()
    {
        super.onPause();

        if (binder != null && binder.getService() != null) {
            binder.getService().checkServiceStatus();
        }

        unbindService(this);
        unregisterReceiver(receiver);
    }


    /**
     * On server status update
     */
    @Override
    public void onStatusUpdate()
    {
    }

    @Override
    public void onResume()
    {
        super.onResume();



        // Start and connect to service
        Intent intent = new Intent(this, IRCService.class);
        intent.setAction(IRCService.ACTION_BACKGROUND);
        startService(intent);
        bindService(intent, this, 0);

        receiver = new ServerReceiver(this);
        registerReceiver(receiver, new IntentFilter(Broadcast.SERVER_UPDATE));

    }


    @Override
    public void onDestroy()
    {
        super.onDestroy();
        instanceCount--;
    }

    /**
     * Options Menu (Menu Button pressed)
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);

        // inflate from xml
        MenuInflater inflater = new MenuInflater(this);
        inflater.inflate(R.menu.servers, menu);

        return super.onCreateOptionsMenu(menu);
    }

    /**
     * On menu item selected

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add:
                startActivityForResult(new Intent(this, AddServerActivity.class), 0);
                break;
            case R.id.about:
                startActivity(new Intent(this, AboutActivity.class));
                break;
            case R.id.settings:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case R.id.disconnect_all:
                ArrayList<Server> mServers = Hermes.getInstance().getServersAsArrayList();
                for (Server server : mServers) {
                    if (binder.getService().hasConnection(server.getId())) {
                        server.setStatus(Status.DISCONNECTED);
                        server.setMayReconnect(false);
                        binder.getService().getConnection(server.getId()).quitServer();
                    }
                }
                // ugly
                binder.getService().stopForegroundCompat(R.string.app_name);
        }

        return super.onOptionsItemSelected(item);
    }
*/

    /**
     * Service connected to Activity
     */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service)
    {
        binder = (IRCBinder) service;
    }

    /**
     * Service disconnected from Activity
     */
    @Override
    public void onServiceDisconnected(ComponentName name)
    {
        binder = null;
    }

}

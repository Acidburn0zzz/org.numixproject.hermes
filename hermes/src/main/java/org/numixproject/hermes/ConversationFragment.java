package org.numixproject.hermes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jibble.pircbot.PircBot;
import org.numixproject.hermes.R;
import org.numixproject.hermes.Hermes;
import org.numixproject.hermes.activity.JoinActivity;
import org.numixproject.hermes.activity.UserActivity;
import org.numixproject.hermes.activity.UsersActivity;
import org.numixproject.hermes.adapter.ConversationPagerAdapter;
import org.numixproject.hermes.adapter.MessageListAdapter;
import org.numixproject.hermes.command.CommandParser;
import org.numixproject.hermes.indicator.ConversationIndicator;
import org.numixproject.hermes.indicator.ConversationTitlePageIndicator.IndicatorStyle;
import org.numixproject.hermes.irc.IRCBinder;
import org.numixproject.hermes.irc.IRCConnection;
import org.numixproject.hermes.irc.IRCService;
import org.numixproject.hermes.listener.ConversationListener;
import org.numixproject.hermes.listener.ServerListener;
import org.numixproject.hermes.listener.SpeechClickListener;
import org.numixproject.hermes.model.Broadcast;
import org.numixproject.hermes.model.Conversation;
import org.numixproject.hermes.model.Extra;
import org.numixproject.hermes.model.Message;
import org.numixproject.hermes.model.Query;
import org.numixproject.hermes.model.Scrollback;
import org.numixproject.hermes.model.Server;
import org.numixproject.hermes.model.ServerInfo;
import org.numixproject.hermes.model.Settings;
import org.numixproject.hermes.model.Status;
import org.numixproject.hermes.model.User;
import org.numixproject.hermes.receiver.ConversationReceiver;
import org.numixproject.hermes.receiver.ServerReceiver;
import org.numixproject.hermes.utils.AndroidBug5497Workaround;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.speech.RecognizerIntent;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.text.method.TextKeyListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;


/**
 * DO NOT EDIT!!! WE DON'T USE FRAGMENT HERE ANYMORE.
 */

public class ConversationFragment extends Fragment implements ConversationListener {
    public static final int REQUEST_CODE_SPEECH = 99;

    private static final int REQUEST_CODE_JOIN = 1;
    private static final int REQUEST_CODE_USERS = 2;
    private static final int REQUEST_CODE_USER = 3;
    private static final int REQUEST_CODE_NICK_COMPLETION = 4;

    private int serverId;
    private Server server;
    private IRCBinder binder;
    private ConversationReceiver channelReceiver;
    private ServerReceiver serverReceiver;

    private ViewPager pager;
    private ConversationIndicator indicator;
    private ConversationPagerAdapter pagerAdapter;

    private Scrollback scrollback;
    private Menu menu;


    // XXX: This is ugly. This is a buffer for a channel that should be joined after showing the
    //      JoinActivity. As onActivityResult() is called before onResume() a "channel joined"
    //      broadcast may get lost as the broadcast receivers are registered in onResume() but the
    //      join command would be called in onActivityResult(). joinChannelBuffer will save the
    //      channel name in onActivityResult() and run the join command in onResume().
    private String joinChannelBuffer;

    private int historySize;

    private boolean reconnectDialogActive = false;

    private final OnKeyListener inputKeyListener = new OnKeyListener() {
        /**
         * On key pressed (input line)
         */
        @Override
        public boolean onKey(View view, int keyCode, KeyEvent event) {
            EditText input = (EditText) view;

            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                String message = scrollback.goBack();
                if (message != null) {
                    input.setText(message);
                }
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                String message = scrollback.goForward();
                if (message != null) {
                    input.setText(message);
                }
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                sendMessage(input.getText().toString());

                // Workaround for a race condition in EditText
                // Instead of calling input.setText("");
                // See:
                // - https://github.com/pocmo/Yaaic/issues/67
                // - http://code.google.com/p/android/issues/detail?id=17508
                TextKeyListener.clear(input.getText());

                return true;
            }

            // Nick completion
            if (keyCode == KeyEvent.KEYCODE_SEARCH) {
                doNickCompletion(input);
                return true;
            }

            return false;
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);

        FragmentActivity faActivity = (FragmentActivity) super.getActivity();
        // Replace LinearLayout by the type of the root element of the layout you're trying to load
        LinearLayout llLayout = (LinearLayout) inflater.inflate(R.layout.conversations, container, false);

        serverId = super.getActivity().getIntent().getExtras().getInt("serverId");
        server = Hermes.getInstance().getServerById(serverId);
        Settings settings = new Settings(super.getActivity());
        // Don't use this method, it's handled by inflater.inflate() above :
        // setContentView(R.layout.activity_layout);

        // The FragmentActivity doesn't contain the layout directly so we must use our instance of     LinearLayout :


        // Finish activity if server does not exist anymore - See #55
        if (server == null) {
            super.getActivity().finish();
        }

        super.getActivity().setTitle(server.getTitle());

        boolean isLandscape = (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);

        EditText input = (EditText) llLayout.findViewById(R.id.input);
        input.setOnKeyListener(inputKeyListener);

        pager = (ViewPager) llLayout.findViewById(R.id.pager);

        pagerAdapter = new ConversationPagerAdapter(getActivity(), server);
        pager.setAdapter(pagerAdapter);


        final float density = getResources().getDisplayMetrics().density;

        indicator = (ConversationIndicator) llLayout.findViewById(R.id.titleIndicator);
        indicator.setServer(server);
        indicator.setViewPager(pager);
        indicator.setFooterColor(Color.parseColor("#d1d1d1"));
        indicator.setFooterLineHeight(1);
        indicator.setPadding(10, 10, 10, 10);
        indicator.setFooterIndicatorStyle(IndicatorStyle.None);
        indicator.setSelectedColor(0xFF222222);
        indicator.setSelectedBold(false);
        indicator.setBackgroundColor(Color.parseColor("#fff5f5f5"));

        historySize = settings.getHistorySize();

        if (server.getStatus() == Status.PRE_CONNECTING) {
            server.clearConversations();
            pagerAdapter.clearConversations();
            server.getConversation(ServerInfo.DEFAULT_NAME).setHistorySize(historySize);
        }

        float fontSize = settings.getFontSize();
        indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 35);

        input.setTypeface(Typeface.SANS_SERIF);

        // Optimization : cache field lookups
        Collection<Conversation> mConversations = server.getConversations();

        for (Conversation conversation : mConversations) {
            // Only scroll to new conversation if it was selected before
            if (conversation.getStatus() == Conversation.STATUS_SELECTED) {
                onNewConversation(conversation.getName());
            } else {
                createNewConversation(conversation.getName());
            }
        }

        int setInputTypeFlags = 0;

        setInputTypeFlags |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;

        if (settings.autoCapSentences()) {
            setInputTypeFlags |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        }

        if (isLandscape && settings.imeExtract()) {
            setInputTypeFlags |= InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE;
        }

        if (!settings.imeExtract()) {
            input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        }

        input.setInputType(input.getInputType() | setInputTypeFlags);

        input.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSoftKeyboard(v);
            }

        });

        // Create a new scrollback history
        scrollback = new Scrollback();

        llLayout.findViewById(R.id.conversationFragment);

        // Handle resizing when Keyboard is up
        getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        return llLayout; // We must return the loaded Layout
    }

    /**
     * On options menu requested
     */
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.conversations, menu);
        super.onCreateOptionsMenu(menu,inflater);

    }

    /**
     * On menu item selected
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case  android.R.id.home:
                super.getActivity().finish();
                break;

            case R.id.disconnect:
                server.setStatus(Status.DISCONNECTED);
                server.setMayReconnect(false);
                binder.getService().getConnection(serverId).quitServer();
                server.clearConversations();
                super.getActivity().setResult(super.getActivity().RESULT_OK);
                super.getActivity().finish();
                break;

            case R.id.close:
                Conversation conversationToClose = pagerAdapter.getItem(pager.getCurrentItem());
                // Make sure we part a channel when closing the channel conversation
                if (conversationToClose.getType() == Conversation.TYPE_CHANNEL) {
                    binder.getService().getConnection(serverId).partChannel(conversationToClose.getName());
                }
                else if (conversationToClose.getType() == Conversation.TYPE_QUERY) {
                    server.removeConversation(conversationToClose.getName());
                    onRemoveConversation(conversationToClose.getName());
                } else {
                    Toast.makeText(super.getActivity(), getResources().getString(R.string.close_server_window), Toast.LENGTH_SHORT).show();
                }
                break;

            case R.id.join:
                startActivityForResult(new Intent(super.getActivity(), JoinActivity.class), REQUEST_CODE_JOIN);
                break;

            case R.id.users:
                Conversation conversationForUserList = pagerAdapter.getItem(pager.getCurrentItem());
                if (conversationForUserList.getType() == Conversation.TYPE_CHANNEL) {
                    Intent intent = new Intent(super.getActivity(), UsersActivity.class);
                    intent.putExtra(
                            Extra.USERS,
                            binder.getService().getConnection(server.getId()).getUsersAsStringArray(
                                    conversationForUserList.getName()
                            )
                    );
                    startActivityForResult(intent, REQUEST_CODE_USERS);
                } else {
                    Toast.makeText(super.getActivity(), getResources().getString(R.string.only_usable_from_channel), Toast.LENGTH_SHORT).show();
                }
                break;
        }

        return true;
    }

    /**
     * Get server object assigned to this activity
     *
     * @return the server object
     */
    public Server getServer()
    {
        return server;
    }

    /**
     * On conversation message
     */
    @Override
    public void onConversationMessage(String target)
    {
        Conversation conversation = server.getConversation(target);

        if (conversation == null) {
            // In an early state it can happen that the conversation object
            // is not created yet.
            return;
        }

        MessageListAdapter adapter = pagerAdapter.getItemAdapter(target);

        while(conversation.hasBufferedMessages()) {
            Message message = conversation.pollBufferedMessage();

            if (adapter != null && message != null) {
                adapter.addMessageCard(message);
                int status;

                switch (message.getType())
                {
                    case Message.TYPE_MISC:
                        status = Conversation.STATUS_MISC;
                        break;

                    default:
                        status = Conversation.STATUS_MESSAGE;
                        break;
                }
                conversation.setStatus(status);
            }
        }

        indicator.updateStateColors();
    }

    /**
     * On new conversation
     */
    @Override
    public void onNewConversation(String target)
    {
        createNewConversation(target);

        pager.setCurrentItem(pagerAdapter.getCount() - 1);
    }

    /**
     * Create a new conversation in the pager adapter for the
     * given target conversation.
     *
     * @param target
     */
    public void createNewConversation(String target)
    {
        pagerAdapter.addConversation(server.getConversation(target));
    }

    /**
     * On conversation remove
     */
    @Override
    public void onRemoveConversation(String target)
    {
        int position = pagerAdapter.getPositionByName(target);

        if (position != -1) {
            pagerAdapter.removeConversation(position);
        }
    }

    /**
     * On topic change
     */
    @Override
    public void onTopicChanged(String target)
    {
        // No implementation
    }

    /**
     * On activity result
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (resultCode != super.getActivity().RESULT_OK) {
            // ignore other result codes
            return;
        }

        switch (requestCode) {
            case REQUEST_CODE_SPEECH:
                ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (matches.size() > 0) {
                    ((EditText) getView().findViewById(R.id.input)).setText(matches.get(0));
                }
                break;
            case REQUEST_CODE_JOIN:
                joinChannelBuffer = data.getExtras().getString("channel");
                break;
            case REQUEST_CODE_USERS:
                Intent intent = new Intent(super.getActivity(), UserActivity.class);
                intent.putExtra(Extra.USER, data.getStringExtra(Extra.USER));
                startActivityForResult(intent, REQUEST_CODE_USER);
                break;
            case REQUEST_CODE_NICK_COMPLETION:
                insertNickCompletion((EditText) getView().findViewById(R.id.input), data.getExtras().getString(Extra.USER));
                break;
            case REQUEST_CODE_USER:
                final int actionId = data.getExtras().getInt(Extra.ACTION);
                final String nickname = data.getExtras().getString(Extra.USER);
                final IRCConnection connection = binder.getService().getConnection(server.getId());
                final String conversation = server.getSelectedConversation();
                final Handler handler = new Handler();

                // XXX: Implement me - The action should be handled after onResume()
                //                     to catch the broadcasts... now we just wait a second
                // Yes .. that's very ugly - we need some kind of queue that is handled after onResume()

                new Thread() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            // Do nothing
                        }

                        String nicknameWithoutPrefix = nickname;

                        while (
                                nicknameWithoutPrefix.startsWith("@") ||
                                        nicknameWithoutPrefix.startsWith("+") ||
                                        nicknameWithoutPrefix.startsWith(".") ||
                                        nicknameWithoutPrefix.startsWith("%")
                                ) {
                            // Strip prefix(es) now
                            nicknameWithoutPrefix = nicknameWithoutPrefix.substring(1);
                        }

                        switch (actionId) {
                            case User.ACTION_REPLY:
                                final String replyText = nicknameWithoutPrefix + ": ";
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        EditText input = (EditText) getView().findViewById(R.id.input);
                                        input.setText(replyText);
                                        input.setSelection(replyText.length());
                                    }
                                });
                                break;
                            case User.ACTION_QUERY:
                                Conversation query = server.getConversation(nicknameWithoutPrefix);
                                if (query == null) {
                                    // Open a query if there's none yet
                                    query = new Query(nicknameWithoutPrefix);
                                    query.setHistorySize(binder.getService().getSettings().getHistorySize());
                                    server.addConversation(query);

                                    Intent intent = Broadcast.createConversationIntent(
                                            Broadcast.CONVERSATION_NEW,
                                            server.getId(),
                                            nicknameWithoutPrefix
                                    );
                                    binder.getService().sendBroadcast(intent);
                                }
                                break;
                            case User.ACTION_OP:
                                connection.op(conversation, nicknameWithoutPrefix);
                                break;
                            case User.ACTION_DEOP:
                                connection.deOp(conversation, nicknameWithoutPrefix);
                                break;
                            case User.ACTION_VOICE:
                                connection.voice(conversation, nicknameWithoutPrefix);
                                break;
                            case User.ACTION_DEVOICE:
                                connection.deVoice(conversation, nicknameWithoutPrefix);
                                break;
                            case User.ACTION_KICK:
                                connection.kick(conversation, nicknameWithoutPrefix);
                                break;
                            case User.ACTION_BAN:
                                connection.ban(conversation, nicknameWithoutPrefix + "!*@*");
                                break;
                        }
                    }
                }.start();

                break;
        }
    }

    /**
     * Send a message in this conversation
     *
     * @param text The text of the message
     */
    private void sendMessage(String text) {
        if (text.equals("")) {
            // ignore empty messages
            return;
        }

        if (!server.isConnected()) {
            Message message = new Message(getString(R.string.message_not_connected));
            message.setColor(Message.COLOR_RED);
            message.setIcon(R.drawable.error);
            server.getConversation(server.getSelectedConversation()).addMessage(message);
            onConversationMessage(server.getSelectedConversation());
        }

        scrollback.addMessage(text);

        Conversation conversation = pagerAdapter.getItem(pager.getCurrentItem());

        if (conversation != null) {
            if (!text.trim().startsWith("/")) {
                if (conversation.getType() != Conversation.TYPE_SERVER) {
                    String nickname = binder.getService().getConnection(serverId).getNick();
                    //conversation.addMessage(new Message(" " + nickname + "- " + text));
                    conversation.addMessage(new Message(text, nickname));
                    binder.getService().getConnection(serverId).sendMessage(conversation.getName(), text);
                } else {
                    Message message = new Message(getString(R.string.chat_only_form_channel));
                    message.setColor(Message.COLOR_YELLOW);
                    message.setIcon(R.drawable.warning);
                    conversation.addMessage(message);
                }
                onConversationMessage(conversation.getName());
            } else {
                CommandParser.getInstance().parse(text, server, conversation, binder.getService());
            }
        }
    }

    /**
     * Complete a nick in the input line
     */
    private void doNickCompletion(EditText input) {
        String text = input.getText().toString();

        if (text.length() <= 0) {
            return;
        }

        String[] tokens = text.split("[\\s,.-]+");

        if (tokens.length <= 0) {
            return;
        }

        String word = tokens[tokens.length - 1].toLowerCase();
        tokens[tokens.length - 1] = null;

        int begin   = input.getSelectionStart();
        int end     = input.getSelectionEnd();
        int cursor  = Math.min(begin, end);
        int sel_end = Math.max(begin, end);

        boolean in_selection = (cursor != sel_end);

        if (in_selection) {
            word = text.substring(cursor, sel_end);
        } else {
            // use the word at the curent cursor position
            while (true) {
                cursor -= 1;
                if (cursor <= 0 || text.charAt(cursor) == ' ') {
                    break;
                }
            }

            if (cursor < 0) {
                cursor = 0;
            }

            if (text.charAt(cursor) == ' ') {
                cursor += 1;
            }

            sel_end = text.indexOf(' ', cursor);

            if (sel_end == -1) {
                sel_end = text.length();
            }

            word = text.substring(cursor, sel_end);
        }
        // Log.d("Yaaic", "Trying to complete nick: " + word);

        Conversation conversationForUserList = pagerAdapter.getItem(pager.getCurrentItem());

        String[] users = null;

        if (conversationForUserList.getType() == Conversation.TYPE_CHANNEL) {
            users = binder.getService().getConnection(server.getId()).getUsersAsStringArray(
                    conversationForUserList.getName()
            );
        }

        // go through users and add matches
        if (users != null) {
            List<Integer> result = new ArrayList<Integer>();

            for (int i = 0; i < users.length; i++) {
                String nick = removeStatusChar(users[i].toLowerCase());
                if (nick.startsWith(word.toLowerCase())) {
                    result.add(Integer.valueOf(i));
                }
            }

            if (result.size() == 1) {
                input.setSelection(cursor, sel_end);
                insertNickCompletion(input, users[result.get(0).intValue()]);
            } else if (result.size() > 0) {
                Intent intent  = new Intent(super.getActivity(), UsersActivity.class);
                String[] extra = new String[result.size()];
                int i = 0;

                for (Integer n : result) {
                    extra[i++] = users[n.intValue()];
                }

                input.setSelection(cursor, sel_end);
                intent.putExtra(Extra.USERS, extra);
                startActivityForResult(intent, REQUEST_CODE_NICK_COMPLETION);
            }
        }
    }

    /**
     * Insert a given nick completion into the input line
     *
     * @param input The input line widget, with the incomplete nick selected
     * @param nick The completed nick
     */
    private void insertNickCompletion(EditText input, String nick) {
        int start = input.getSelectionStart();
        int end  = input.getSelectionEnd();
        nick = removeStatusChar(nick);

        if (start == 0) {
            nick += ":";
        }

        nick += " ";
        input.getText().replace(start, end, nick, 0, nick.length());
        // put cursor after inserted text
        input.setSelection(start + nick.length());
        input.clearComposingText();
        input.post(new Runnable() {
            @Override
            public void run() {
                // make the softkeyboard come up again (only if no hw keyboard is attached)
                EditText input = (EditText) getView().findViewById(R.id.input);
                openSoftKeyboard(input);
            }
        });

        input.requestFocus();
    }

    /**
     * Open the soft keyboard (helper function)
     */
    private void openSoftKeyboard(View view) {
        ((InputMethodManager) super.getActivity().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /**
     * Remove the status char off the front of a nick if one is present
     *
     * @param nick
     * @return nick without statuschar
     */
    private String removeStatusChar(String nick)
    {
        /* Discard status characters */
        if (nick.startsWith("@") || nick.startsWith("+")
                || nick.startsWith("%")) {
            nick = nick.substring(1);
        }
        return nick;
    }
}

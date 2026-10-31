package com.gymlog.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // ── Data models ──
    static class Exercise {
        String name;
        int sets;
        Exercise(String name) { this.name = name; this.sets = 0; }
    }

    static class DayLog {
        String date;
        List<Exercise> exercises = new ArrayList<>();
        DayLog(String date) { this.date = date; }
    }

    // ── State ──
    private List<DayLog> allLogs = new ArrayList<>();
    private SharedPreferences prefs;
    private Gson gson = new Gson();
    private CountDownTimer restTimer;
    private int restSeconds = 90;
    private boolean restEnabled = true;

    // ── Views ──
    private LinearLayout tabToday, tabHistory, tabStats, tabSettings;
    private View viewToday, viewHistory, viewStats, viewSettings;
    private RecyclerView rvExercises;
    private ExerciseAdapter adapter;
    private TextView tvDate, tvRestCountdown, tvEmptyToday;
    private View restBar;
    private Button btnSkipRest;
    private EditText etNewExercise;
    private LinearLayout llSuggestions;
    private ProgressBar progressRest;

    private final String[] SUGGESTIONS = {"Лежанка","Клек","Мъртва тяга","Бицепс","Трицепс","Рамо преса","Гребане","Набирания","Дипове","Флайс"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("gymlog", MODE_PRIVATE);
        loadData();
        loadSettings();
        bindViews();
        setupTabs();
        setupTodayTab();
        showTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Ако приложението е останало отворено след полунощ, обнови датата и списъка
        updateDateLabel();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            renderSuggestions();
            updateEmptyState();
        }
    }

    @Override
    protected void onDestroy() {
        // Таймерът не трябва да продължава (Toast/вибрация) след затваряне на екрана
        if (restTimer != null) { restTimer.cancel(); restTimer = null; }
        super.onDestroy();
    }

    private void updateDateLabel() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, d MMMM", new Locale("bg"));
        tvDate.setText(sdf.format(new Date()));
    }

    private void bindViews() {
        tabToday    = findViewById(R.id.tabToday);
        tabHistory  = findViewById(R.id.tabHistory);
        tabStats    = findViewById(R.id.tabStats);
        tabSettings = findViewById(R.id.tabSettings);

        viewToday    = findViewById(R.id.viewToday);
        viewHistory  = findViewById(R.id.viewHistory);
        viewStats    = findViewById(R.id.viewStats);
        viewSettings = findViewById(R.id.viewSettings);

        tvDate         = findViewById(R.id.tvDate);
        rvExercises    = findViewById(R.id.rvExercises);
        tvEmptyToday   = findViewById(R.id.tvEmptyToday);
        restBar        = findViewById(R.id.restBar);
        tvRestCountdown= findViewById(R.id.tvRestCountdown);
        btnSkipRest    = findViewById(R.id.btnSkipRest);
        etNewExercise  = findViewById(R.id.etNewExercise);
        llSuggestions  = findViewById(R.id.llSuggestions);
        progressRest   = findViewById(R.id.progressRest);

        // Date
        updateDateLabel();

        // Skip rest
        btnSkipRest.setOnClickListener(v -> stopRest());

        // Add exercise button
        findViewById(R.id.btnAdd).setOnClickListener(v -> addExercise());
        etNewExercise.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { addExercise(); return true; }
            return false;
        });
    }

    private void setupTabs() {
        tabToday.setOnClickListener(v -> showTab(0));
        tabHistory.setOnClickListener(v -> showTab(1));
        tabStats.setOnClickListener(v -> showTab(2));
        tabSettings.setOnClickListener(v -> showTab(3));
    }

    private void showTab(int idx) {
        viewToday.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        viewHistory.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        viewStats.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);

        int[] ids = {R.id.tabToday, R.id.tabHistory, R.id.tabStats, R.id.tabSettings};
        LinearLayout[] tabs = {tabToday, tabHistory, tabStats, tabSettings};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setAlpha(i == idx ? 1f : 0.4f);
        }

        if (idx == 1) renderHistory();
        if (idx == 2) renderStats();
        if (idx == 3) renderSettings();
    }

    // ── TODAY ──
    private void setupTodayTab() {
        rvExercises.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExerciseAdapter();
        rvExercises.setAdapter(adapter);
        renderSuggestions();
        updateEmptyState();
    }

    private void addExercise() {
        String name = etNewExercise.getText().toString().trim();
        if (name.isEmpty()) return;
        DayLog today = getToday();
        for (Exercise e : today.exercises) {
            if (e.name.equalsIgnoreCase(name)) {
                Toast.makeText(this, "Вече съществува!", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        today.exercises.add(new Exercise(name));
        etNewExercise.setText("");
        saveData();
        adapter.notifyDataSetChanged();
        renderSuggestions();
        updateEmptyState();
    }

    private void renderSuggestions() {
        llSuggestions.removeAllViews();
        DayLog today = getToday();
        Set<String> used = new HashSet<>();
        for (Exercise e : today.exercises) used.add(e.name.toLowerCase(Locale.ROOT));

        int count = 0;
        for (String s : SUGGESTIONS) {
            if (used.contains(s.toLowerCase(Locale.ROOT))) continue;
            if (count++ >= 5) break;
            Button chip = new Button(this);
            chip.setText(s);
            chip.setTextSize(11);
            chip.setPadding(24, 8, 24, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 12, 0);
            chip.setLayoutParams(lp);
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setTextColor(0xFF9999BB);
            chip.setOnClickListener(v -> {
                etNewExercise.setText(s);
                addExercise();
            });
            llSuggestions.addView(chip);
        }
    }

    private void updateEmptyState() {
        boolean empty = getToday().exercises.isEmpty();
        tvEmptyToday.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvExercises.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ── REST TIMER ──
    private void startRest() {
        if (!restEnabled) return;
        if (restTimer != null) restTimer.cancel();
        restBar.setVisibility(View.VISIBLE);
        progressRest.setMax(restSeconds);
        progressRest.setProgress(restSeconds);

        restTimer = new CountDownTimer(restSeconds * 1000L, 1000) {
            @Override public void onTick(long ms) {
                int rem = (int)(ms / 1000);
                progressRest.setProgress(rem);
                if (rem >= 60) {
                    tvRestCountdown.setText(rem/60 + ":" + String.format("%02d", rem%60));
                } else {
                    tvRestCountdown.setText(String.valueOf(rem));
                }
            }
            @Override public void onFinish() {
                stopRest();
                vibrate();
                Toast.makeText(MainActivity.this, "💪 Почивката приключи!", Toast.LENGTH_SHORT).show();
            }
        }.start();
    }

    private void stopRest() {
        if (restTimer != null) { restTimer.cancel(); restTimer = null; }
        restBar.setVisibility(View.GONE);
    }

    private void vibrate() {
        // Вибрацията е само подсказка: никога не трябва да срива приложението
        // (например при липсващо разрешение или устройство без вибратор).
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 100, 50, 100}, -1));
            }
        } catch (RuntimeException ignored) {
        }
    }

    // ── HISTORY ──
    private void renderHistory() {
        LinearLayout ll = findViewById(R.id.llHistory);
        ll.removeAllViews();

        if (allLogs.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Няма история");
            tv.setTextColor(0xFF6B6B85);
            tv.setPadding(0, 60, 0, 0);
            tv.setGravity(android.view.Gravity.CENTER);
            ll.addView(tv);
            return;
        }

        List<DayLog> sorted = new ArrayList<>(allLogs);
        Collections.sort(sorted, (a, b) -> b.date.compareTo(a.date));

        for (DayLog log : sorted) {
            if (log.exercises.isEmpty()) continue;

            TextView tvDay = new TextView(this);
            tvDay.setText(formatDate(log.date));
            tvDay.setTextColor(0xFF6B6B85);
            tvDay.setTextSize(11);
            tvDay.setPadding(0, 24, 0, 8);
            ll.addView(tvDay);

            for (Exercise ex : log.exercises) {
                View row = LayoutInflater.from(this).inflate(R.layout.item_history, ll, false);
                ((TextView)row.findViewById(R.id.tvHName)).setText(ex.name);
                ((TextView)row.findViewById(R.id.tvHSets)).setText(String.valueOf(ex.sets));
                ll.addView(row);
            }
        }
    }

    // ── STATS ──
    private void renderStats() {
        LinearLayout ll = findViewById(R.id.llStats);
        ll.removeAllViews();

        Map<String, Integer> totals = new LinkedHashMap<>();
        for (DayLog log : allLogs) {
            for (Exercise ex : log.exercises) {
                totals.merge(ex.name, ex.sets, Integer::sum);
            }
        }

        if (totals.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Няма данни. Тренирай! 💪");
            tv.setTextColor(0xFF6B6B85);
            tv.setPadding(0, 60, 0, 0);
            tv.setGravity(android.view.Gravity.CENTER);
            ll.addView(tv);
            return;
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue() - a.getValue());

        for (Map.Entry<String, Integer> entry : sorted) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_stat, ll, false);
            ((TextView)card.findViewById(R.id.tvStatName)).setText(entry.getKey());
            ((TextView)card.findViewById(R.id.tvStatTotal)).setText(String.valueOf(entry.getValue()));
            ll.addView(card);
        }
    }

    // ── SETTINGS ──
    private void renderSettings() {
        Switch swRest = findViewById(R.id.swRest);
        swRest.setChecked(restEnabled);
        swRest.setOnCheckedChangeListener((b, checked) -> {
            restEnabled = checked;
            saveSettings();
        });

        RadioGroup rgRest = findViewById(R.id.rgRestTime);
        int[] times = {30, 60, 90, 120};
        int[] radioIds = {R.id.rb30, R.id.rb60, R.id.rb90, R.id.rb120};
        for (int i = 0; i < times.length; i++) {
            if (times[i] == restSeconds) { rgRest.check(radioIds[i]); break; }
        }
        rgRest.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rb30) restSeconds = 30;
            else if (id == R.id.rb60) restSeconds = 60;
            else if (id == R.id.rb90) restSeconds = 90;
            else restSeconds = 120;
            saveSettings();
        });

        findViewById(R.id.btnClearToday).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Изтриване")
                .setMessage("Изтриване на днешната тренировка?")
                .setPositiveButton("Да", (d, w) -> {
                    getToday().exercises.clear();
                    saveData();
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                    renderSuggestions();
                    Toast.makeText(this, "Изтрито", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отказ", null).show();
        });

        findViewById(R.id.btnClearAll).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Изтриване")
                .setMessage("Изтриване на цялата история?")
                .setPositiveButton("Да", (d, w) -> {
                    allLogs.clear();
                    saveData();
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                    renderSuggestions();
                    Toast.makeText(this, "Изтрито всичко", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отказ", null).show();
        });
    }

    // ── ADAPTER ──
    class ExerciseAdapter extends RecyclerView.Adapter<ExerciseAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCount;
            Button btnAdd, btnRemove, btnDelete;
            LinearLayout llChips;
            VH(View v) {
                super(v);
                tvName   = v.findViewById(R.id.tvExName);
                tvCount  = v.findViewById(R.id.tvSetCount);
                btnAdd   = v.findViewById(R.id.btnAddSet);
                btnRemove= v.findViewById(R.id.btnRemoveSet);
                btnDelete= v.findViewById(R.id.btnDeleteEx);
                llChips  = v.findViewById(R.id.llChips);
            }
        }

        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_exercise, p, false));
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            Exercise ex = getToday().exercises.get(pos);
            h.tvName.setText(ex.name);
            h.tvCount.setText("Серии: " + ex.sets);

            // Draw chips
            h.llChips.removeAllViews();
            for (int i = 0; i < ex.sets; i++) {
                TextView chip = new TextView(MainActivity.this);
                chip.setText(String.valueOf(i + 1));
                chip.setTextColor(0xFF000000);
                chip.setBackgroundResource(R.drawable.chip_done);
                chip.setTextSize(14);
                chip.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(80, 80);
                lp.setMargins(0, 0, 8, 0);
                chip.setLayoutParams(lp);
                h.llChips.addView(chip);
            }

            h.btnAdd.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                ex.sets++;
                saveData();
                notifyItemChanged(p);
                startRest();
                vibrate();
            });

            h.btnRemove.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                if (ex.sets > 0) { ex.sets--; saveData(); notifyItemChanged(p); }
            });

            h.btnDelete.setOnClickListener(v -> {
                int p2 = h.getAdapterPosition();
                // При бързо двойно натискане елементът вече е премахнат и позицията е -1
                if (p2 == RecyclerView.NO_POSITION || p2 >= getToday().exercises.size()) return;
                getToday().exercises.remove(p2);
                saveData();
                notifyItemRemoved(p2);
                notifyItemRangeChanged(p2, getItemCount());
                renderSuggestions();
                updateEmptyState();
            });
        }

        @Override public int getItemCount() { return getToday().exercises.size(); }
    }

    // ── HELPERS ──
    // Ключът на деня е винаги с латински цифри, независимо от езика на телефона
    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private DayLog getToday() {
        String key = todayKey();
        for (DayLog log : allLogs) { if (log.date.equals(key)) return log; }
        DayLog log = new DayLog(key);
        allLogs.add(log);
        return log;
    }

    private String formatDate(String dateStr) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEEE, d MMMM", new Locale("bg"));
            return out.format(in.parse(dateStr));
        } catch (Exception e) { return dateStr; }
    }

    private void saveData() {
        prefs.edit().putString("logs", gson.toJson(allLogs)).apply();
    }

    private void loadData() {
        String json = prefs.getString("logs", null);
        if (json == null) return;
        try {
            Type type = new TypeToken<List<DayLog>>(){}.getType();
            List<DayLog> loaded = gson.fromJson(json, type);
            List<DayLog> clean = new ArrayList<>();
            if (loaded != null) {
                for (DayLog log : loaded) {
                    if (log == null || log.date == null) continue;
                    if (log.exercises == null) log.exercises = new ArrayList<>();
                    List<Exercise> exs = new ArrayList<>();
                    for (Exercise ex : log.exercises) {
                        if (ex != null && ex.name != null) exs.add(ex);
                    }
                    log.exercises = exs;
                    clean.add(log);
                }
            }
            allLogs = clean;
        } catch (RuntimeException e) {
            // Повредени данни не трябва да правят приложението неизползваемо.
            // Суровият текст се запазва в резервен ключ, за да не се губи безследно.
            prefs.edit().putString("logs_corrupt_backup", json).apply();
            allLogs = new ArrayList<>();
        }
    }

    private void saveSettings() {
        prefs.edit().putInt("restSeconds", restSeconds).putBoolean("restEnabled", restEnabled).apply();
    }

    private void loadSettings() {
        restSeconds = prefs.getInt("restSeconds", 90);
        restEnabled = prefs.getBoolean("restEnabled", true);
    }
}

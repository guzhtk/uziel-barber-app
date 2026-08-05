package com.uziel.barber

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Calendar

const val REMINDER_CHANNEL_ID = "appt_reminders"

// Fires 3 minutes before an appointment, even if the app itself is closed.
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: ""
        val time = intent.getStringExtra("time") ?: ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID, "תזכורות תורים", NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "התראה 3 דקות לפני תחילת תור"
            channel.enableVibration(true)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val builder = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("תור בעוד 3 דקות")
            .setContentText(if (name.isNotEmpty()) "$name בשעה $time" else "התור הבא בשעה $time")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted - fail silently rather than crash.
        }
    }
}

data class Appt(
    val id: Long,
    val name: String,
    val service: String,
    val mins: Int,
    val day: String, // yyyy-MM-dd
    val time: String // HH:mm
)

/**
 * Fully native UI - no WebView anywhere in this file or in the app.
 * All appointment data lives in SharedPreferences (on-device, private
 * storage, JSON-encoded) - no server, no cloud, no Google services.
 *
 * This build also installs a crash catcher: if anything goes wrong, the
 * error is saved to disk instead of silently killing the app, and shown
 * on screen the *next* time the app is opened - no computer/USB needed
 * to see what happened.
 */
class MainActivity : AppCompatActivity() {

    private val PREFS = "uziel_appointments_prefs"
    private val KEY = "appointments"
    private val CRASH_KEY = "last_crash"

    private lateinit var fname: AutoCompleteTextView
    private lateinit var fservice: Spinner
    private lateinit var selectedDayLabel: TextView
    private lateinit var dayPickerContainer: LinearLayout
    private lateinit var fslots: Spinner
    private lateinit var nextAvailBox: TextView
    private lateinit var fbtn: Button
    private lateinit var confirmBox: TextView
    private lateinit var viewForm: View
    private lateinit var viewSchedule: LinearLayout
    private lateinit var tabForm: Button
    private lateinit var tabSchedule: Button

    private val services = listOf(
        Triple("חסידית", 10, "תספורת חסידית — 10 דקות"),
        Triple("מכונה", 15, "תספורת מכונה — 15 דקות"),
        Triple("פלוס זקן", 20, "תספורת פלוס זקן — 20 דקות")
    )

    private var selectedDay: String? = null
    private var availableSlots: List<Int> = emptyList()
    private var selectedSlotMinutes: Int? = null

    private var calYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var calMonth: Int = Calendar.getInstance().get(Calendar.MONTH)
    private var selectedScheduleDay: String? = null

    private var formCalYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var formCalMonth: Int = Calendar.getInstance().get(Calendar.MONTH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installCrashHandler()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCrash = prefs.getString(CRASH_KEY, null)
        if (lastCrash != null) {
            showCrashScreen(lastCrash)
            return
        }

        try {
            setContentView(R.layout.activity_main)

            createReminderChannel()
            ensureNotificationPermission()

            fname = findViewById(R.id.fname)
            fservice = findViewById(R.id.fservice)
            selectedDayLabel = findViewById(R.id.selectedDayLabel)
            dayPickerContainer = findViewById(R.id.dayPickerContainer)
            fslots = findViewById(R.id.fslots)
            nextAvailBox = findViewById(R.id.nextAvailBox)
            fbtn = findViewById(R.id.fbtn)
            confirmBox = findViewById(R.id.confirmBox)
            viewForm = findViewById(R.id.viewForm)
            viewSchedule = findViewById(R.id.viewSchedule)
            tabForm = findViewById(R.id.tabForm)
            tabSchedule = findViewById(R.id.tabSchedule)

            val serviceLabels = services.map { it.third }
            fservice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, serviceLabels)

            fname.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateSaveState() }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            fservice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    renderSlots()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            fslots.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedSlotMinutes = if (position in availableSlots.indices) availableSlots[position] else null
                    updateSaveState()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            fbtn.setOnClickListener { saveAppointment() }

            tabForm.setOnClickListener { showForm() }
            tabSchedule.setOnClickListener { showSchedule() }

            refreshCustomerSuggestions()
            renderDayPicker()
            renderSlots()
            updateSaveState()
            rescheduleAllReminders()
        } catch (e: Throwable) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            saveCrash(sw.toString())
            showCrashScreen(sw.toString())
        }
    }

    // ---------- Crash catcher ----------

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                saveCrash(sw.toString())
            } catch (ignored: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(text: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(CRASH_KEY, text).apply()
    }

    private fun showCrashScreen(text: String) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 80, 32, 32)

        val title = TextView(this)
        title.text = "האפליקציה נתקלה בשגיאה. אפשר לצלם את המסך הזה ולשלוח כדי שנתקן:"
        title.setTextColor(0xFFA32D2D.toInt())
        title.textSize = 14f
        title.setPadding(0, 0, 0, 24)
        layout.addView(title)

        val tv = TextView(this)
        tv.text = text
        tv.setTextIsSelectable(true)
        tv.textSize = 11f
        layout.addView(tv)

        val btn = Button(this)
        btn.text = "נקה ונסה שוב"
        btn.setOnClickListener {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CRASH_KEY).apply()
            recreate()
        }
        layout.addView(btn)

        scroll.addView(layout)
        setContentView(scroll)
    }

    // ---------- Appointment reminders (3 minutes before) ----------

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID, "תזכורות תורים", NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "התראה 3 דקות לפני תחילת תור"
            channel.enableVibration(true)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun reminderPendingIntent(apptId: Long, name: String, time: String): PendingIntent {
        val intent = Intent(this, ReminderReceiver::class.java)
        intent.putExtra("name", name)
        intent.putExtra("time", time)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, apptId.toInt(), intent, flags)
    }

    private fun scheduleReminder(appt: Appt) {
        try {
            val parts = appt.day.split("-").map { it.toInt() }
            val startMinutes = timeToMinutes(appt.time)
            val cal = Calendar.getInstance()
            cal.set(parts[0], parts[1] - 1, parts[2], startMinutes / 60, startMinutes % 60, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.MINUTE, -3)
            val triggerAt = cal.timeInMillis
            if (triggerAt <= System.currentTimeMillis()) return

            val pi = reminderPendingIntent(appt.id, appt.name, appt.time)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Throwable) {
            // Best-effort: a reminder failing to schedule should never crash the app.
        }
    }

    private fun cancelReminder(apptId: Long) {
        try {
            val pi = reminderPendingIntent(apptId, "", "")
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        } catch (e: Throwable) {
        }
    }

    private fun rescheduleAllReminders() {
        for (a in loadAppointments()) {
            scheduleReminder(a)
        }
    }

    // ---------- Customer autocomplete ----------

    // Builds the suggestion list for the name field straight from the
    // names already used in past appointments - no separate customer
    // database needed.
    private fun refreshCustomerSuggestions() {
        val names = loadAppointments()
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        fname.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names))
    }

    // ---------- Storage (SharedPreferences, on-device only) ----------

    private fun loadAppointments(): MutableList<Appt> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<Appt>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Appt(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    service = o.getString("service"),
                    mins = o.getInt("mins"),
                    day = o.getString("day"),
                    time = o.getString("time")
                )
            )
        }
        return list
    }

    private fun saveAppointments(list: List<Appt>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("name", a.name)
            o.put("service", a.service)
            o.put("mins", a.mins)
            o.put("day", a.day)
            o.put("time", a.time)
            arr.put(o)
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    // ---------- Business hours / slot logic ----------

    private fun pad(n: Int) = if (n < 10) "0$n" else "$n"

    private fun todayStr(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${pad(c.get(Calendar.MONTH) + 1)}-${pad(c.get(Calendar.DAY_OF_MONTH))}"
    }

    private fun addDays(dayStr: String, n: Int): String {
        val parts = dayStr.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        c.add(Calendar.DAY_OF_MONTH, n)
        return "${c.get(Calendar.YEAR)}-${pad(c.get(Calendar.MONTH) + 1)}-${pad(c.get(Calendar.DAY_OF_MONTH))}"
    }

    private fun dayOfWeek(dayStr: String): Int {
        val parts = dayStr.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        return c.get(Calendar.DAY_OF_WEEK)
    }

    // Business hours: open 24 hours every day except Saturday (Shabbat),
    // which stays closed.
    private fun hoursFor(dayStr: String): Pair<Int, Int>? {
        return when (dayOfWeek(dayStr)) {
            Calendar.SATURDAY -> null
            else -> Pair(0, 24 * 60)
        }
    }

    private fun timeToMinutes(t: String): Int {
        val p = t.split(":").map { it.toInt() }
        return p[0] * 60 + p[1]
    }

    private fun minutesToLabel(mins: Int): String {
        return "${pad(mins / 60)}:${pad(mins % 60)}"
    }

    private fun currentDuration(): Int = services[fservice.selectedItemPosition].second

    private fun computeAvailableSlots(dayStr: String, duration: Int): List<Int> {
        val hrs = hoursFor(dayStr) ?: return emptyList()
        val existing = loadAppointments()
            .filter { it.day == dayStr }
            .map { val s = timeToMinutes(it.time); Pair(s, s + it.mins) }
        val slots = mutableListOf<Int>()
        val isToday = dayStr == todayStr()
        val nowMins = if (isToday) {
            val c = Calendar.getInstance()
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        } else -1
        var t = hrs.first
        while (t + duration <= hrs.second) {
            if (!isToday || t >= nowMins) {
                val overlaps = existing.any { t < it.second && (t + duration) > it.first }
                if (!overlaps) slots.add(t)
            }
            t += 5
        }
        return slots
    }

    private fun findNextAvailable(fromDay: String, duration: Int, maxDays: Int = 90): Pair<String, Int>? {
        var day = fromDay
        for (i in 0 until maxDays) {
            day = addDays(day, 1)
            val slots = computeAvailableSlots(day, duration)
            if (slots.isNotEmpty()) return Pair(day, slots.first())
        }
        return null
    }

    // ---------- Hebrew date ----------

    // month is 0-indexed (Calendar.MONTH style). Uses Android's built-in
    // ICU Hebrew calendar - no external library needed.
    private fun hebrewDateLabel(y: Int, month: Int, d: Int, pattern: String = "d MMMM y"): String {
        return try {
            val greg = java.util.GregorianCalendar(y, month, d)
            val millis = greg.timeInMillis
            val heb = android.icu.util.HebrewCalendar()
            heb.timeInMillis = millis
            val fmt = android.icu.text.SimpleDateFormat(pattern, android.icu.util.ULocale("he"))
            fmt.calendar = heb
            fmt.format(java.util.Date(millis))
        } catch (e: Throwable) {
            ""
        }
    }

    // ---------- UI ----------

    private fun renderDayPicker() {
        dayPickerContainer.removeAllViews()

        val monthNames = arrayOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = android.view.Gravity.CENTER_VERTICAL
        navRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val prevBtn = Button(this)
        prevBtn.text = "◀"
        prevBtn.setOnClickListener {
            formCalMonth -= 1
            if (formCalMonth < 0) { formCalMonth = 11; formCalYear -= 1 }
            renderDayPicker()
        }

        val monthLabel = TextView(this)
        monthLabel.text = "${monthNames[formCalMonth]} $formCalYear"
        monthLabel.textSize = 15f
        monthLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        monthLabel.gravity = android.view.Gravity.CENTER
        monthLabel.setTextColor(0xFF2C2C2A.toInt())
        monthLabel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val nextBtn = Button(this)
        nextBtn.text = "▶"
        nextBtn.setOnClickListener {
            formCalMonth += 1
            if (formCalMonth > 11) { formCalMonth = 0; formCalYear += 1 }
            renderDayPicker()
        }

        navRow.addView(prevBtn)
        navRow.addView(monthLabel)
        navRow.addView(nextBtn)
        dayPickerContainer.addView(navRow)

        val weekdayShort = arrayOf("א", "ב", "ג", "ד", "ה", "ו", "ש")
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        for (w in weekdayShort) {
            val tv = TextView(this)
            tv.text = w
            tv.gravity = android.view.Gravity.CENTER
            tv.setTextColor(0xFF5F5E5A.toInt())
            tv.textSize = 11f
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            headerRow.addView(tv)
        }
        dayPickerContainer.addView(headerRow)

        val firstOfMonth = Calendar.getInstance()
        firstOfMonth.set(formCalYear, formCalMonth, 1)
        val startDow = firstOfMonth.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = firstOfMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = todayStr()

        var dayCounter = 1 - startDow
        while (dayCounter <= daysInMonth) {
            val weekRow = LinearLayout(this)
            weekRow.orientation = LinearLayout.HORIZONTAL
            weekRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            for (col in 0..6) {
                val cellDay = dayCounter
                val cellParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                if (cellDay in 1..daysInMonth) {
                    val dateKey = "$formCalYear-${pad(formCalMonth + 1)}-${pad(cellDay)}"
                    val isToday = dateKey == todayKey
                    val isSelected = dateKey == selectedDay
                    val isPast = dateKey < todayKey
                    val closed = hoursFor(dateKey) == null
                    val disabled = isPast || closed

                    val cell = LinearLayout(this)
                    cell.orientation = LinearLayout.VERTICAL
                    cell.gravity = android.view.Gravity.CENTER
                    cell.layoutParams = cellParams
                    cell.setPadding(2, 6, 2, 6)
                    cell.setBackgroundColor(
                        when {
                            isSelected -> 0xFF712B13.toInt()
                            isToday -> 0xFFEFE3DC.toInt()
                            else -> 0x00000000
                        }
                    )

                    val dayTv = TextView(this)
                    dayTv.text = "$cellDay"
                    dayTv.gravity = android.view.Gravity.CENTER
                    dayTv.textSize = 13f
                    val fadedColor = if (disabled) 0xFFBFBDB4.toInt() else 0xFF2C2C2A.toInt()
                    dayTv.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else fadedColor)
                    cell.addView(dayTv)

                    val hebDay = hebrewDateLabel(formCalYear, formCalMonth, cellDay, "d")
                    if (hebDay.isNotEmpty()) {
                        val hebTv = TextView(this)
                        hebTv.text = hebDay
                        hebTv.gravity = android.view.Gravity.CENTER
                        hebTv.textSize = 9f
                        val fadedHeb = if (disabled) 0xFFBFBDB4.toInt() else 0xFF5F5E5A.toInt()
                        hebTv.setTextColor(if (isSelected) 0xFFFAECE7.toInt() else fadedHeb)
                        cell.addView(hebTv)
                    }

                    if (!disabled) {
                        cell.setOnClickListener {
                            selectedDay = dateKey
                            val heb = hebrewDateLabel(formCalYear, formCalMonth, cellDay)
                            val weekdays = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
                            val dow = dayOfWeek(dateKey) - 1
                            selectedDayLabel.text = "נבחר: יום ${weekdays[dow]}, $cellDay/${formCalMonth + 1}/$formCalYear" +
                                if (heb.isNotEmpty()) " ($heb)" else ""
                            renderDayPicker()
                            renderSlots()
                        }
                    }

                    weekRow.addView(cell)
                } else {
                    val empty = View(this)
                    empty.layoutParams = cellParams
                    weekRow.addView(empty)
                }
                dayCounter++
            }
            dayPickerContainer.addView(weekRow)
        }
    }

    private fun renderSlots() {
        selectedSlotMinutes = null
        nextAvailBox.text = ""
        nextAvailBox.setOnClickListener(null)
        val day = selectedDay
        if (day == null) {
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("בחר קודם יום"))
            availableSlots = emptyList()
            updateSaveState()
            return
        }
        val duration = currentDuration()
        val hrs = hoursFor(day)
        if (hrs == null) {
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("סגור ביום זה"))
            availableSlots = emptyList()
            showNextAvailable(day, duration)
            updateSaveState()
            return
        }
        val slots = computeAvailableSlots(day, duration)
        availableSlots = slots
        if (slots.isEmpty()) {
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("אין שעות פנויות"))
            showNextAvailable(day, duration)
        } else {
            val labels = slots.mapIndexed { idx, t ->
                if (idx == 0) "${minutesToLabel(t)} (מומלץ)" else minutesToLabel(t)
            }
            fslots.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            selectedSlotMinutes = slots.first()
        }
        updateSaveState()
    }

    private fun showNextAvailable(day: String, duration: Int) {
        val found = findNextAvailable(day, duration)
        if (found == null) {
            nextAvailBox.text = "לא נמצא תור פנוי בשלושת החודשים הקרובים."
            return
        }
        val (fDay, fSlot) = found
        val parts = fDay.split("-").map { it.toInt() }
        val weekdays = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
        val dow = dayOfWeek(fDay) - 1
        val heb = hebrewDateLabel(parts[0], parts[1] - 1, parts[2])
        val hebPart = if (heb.isNotEmpty()) " ($heb)" else ""
        nextAvailBox.text = "התור הפנוי הבא: יום ${weekdays[dow]}, ${parts[2]}/${parts[1]}/${parts[0]}$hebPart בשעה ${minutesToLabel(fSlot)}\n(הקש כאן כדי לעבור לתאריך הזה)"
        nextAvailBox.setOnClickListener {
            selectedDay = fDay
            formCalYear = parts[0]
            formCalMonth = parts[1] - 1
            selectedDayLabel.text = "נבחר: יום ${weekdays[dow]}, ${parts[2]}/${parts[1]}/${parts[0]}$hebPart"
            renderDayPicker()
            renderSlots()
        }
    }

    private fun updateSaveState() {
        val name = fname.text.toString().trim()
        fbtn.isEnabled = name.isNotEmpty() && selectedDay != null && selectedSlotMinutes != null
    }

    private fun saveAppointment() {
        val name = fname.text.toString().trim()
        val day = selectedDay ?: return
        val slot = selectedSlotMinutes ?: return
        val service = services[fservice.selectedItemPosition]
        val label = service.first
        val mins = service.second

        val fresh = computeAvailableSlots(day, mins)
        if (!fresh.contains(slot)) {
            Toast.makeText(this, "השעה שנבחרה כבר אינה פנויה, בחר שעה אחרת", Toast.LENGTH_LONG).show()
            renderSlots()
            return
        }

        val list = loadAppointments()
        val newAppt = Appt(System.currentTimeMillis(), name, label, mins, day, minutesToLabel(slot))
        list.add(newAppt)
        saveAppointments(list)
        scheduleReminder(newAppt)

        val parts = day.split("-")
        val hebSave = hebrewDateLabel(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        confirmBox.visibility = View.VISIBLE
        confirmBox.text = "התור נקבע בהצלחה\nשם: $name\nסוג תספורת: $label ($mins דקות)\nיום: ${parts[2]}/${parts[1]}/${parts[0]}" +
            (if (hebSave.isNotEmpty()) " ($hebSave)" else "") + "\nשעה: ${minutesToLabel(slot)}"

        fname.setText("")
        selectedDay = null
        selectedDayLabel.text = "לא נבחר יום"
        renderDayPicker()
        renderSlots()
        refreshCustomerSuggestions()
    }

    private fun showForm() {
        viewForm.visibility = View.VISIBLE
        viewSchedule.visibility = View.GONE
        tabForm.setBackgroundColor(0xFF712B13.toInt())
        tabForm.setTextColor(0xFFFFFFFF.toInt())
        tabSchedule.setBackgroundColor(0xFFFFFFFF.toInt())
        tabSchedule.setTextColor(0xFF5F5E5A.toInt())
    }

    private fun showSchedule() {
        viewForm.visibility = View.GONE
        viewSchedule.visibility = View.VISIBLE
        tabSchedule.setBackgroundColor(0xFF712B13.toInt())
        tabSchedule.setTextColor(0xFFFFFFFF.toInt())
        tabForm.setBackgroundColor(0xFFFFFFFF.toInt())
        tabForm.setTextColor(0xFF5F5E5A.toInt())
        renderSchedule()
    }

    private fun renderSchedule() {
        viewSchedule.removeAllViews()
        val all = loadAppointments()

        val monthNames = arrayOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

        // Month navigation header
        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = android.view.Gravity.CENTER_VERTICAL
        navRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val prevBtn = Button(this)
        prevBtn.text = "◀"
        prevBtn.setOnClickListener {
            calMonth -= 1
            if (calMonth < 0) { calMonth = 11; calYear -= 1 }
            selectedScheduleDay = null
            renderSchedule()
        }

        val monthLabel = TextView(this)
        monthLabel.text = "${monthNames[calMonth]} $calYear"
        monthLabel.textSize = 16f
        monthLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        monthLabel.gravity = android.view.Gravity.CENTER
        monthLabel.setTextColor(0xFF2C2C2A.toInt())
        monthLabel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val nextBtn = Button(this)
        nextBtn.text = "▶"
        nextBtn.setOnClickListener {
            calMonth += 1
            if (calMonth > 11) { calMonth = 0; calYear += 1 }
            selectedScheduleDay = null
            renderSchedule()
        }

        navRow.addView(prevBtn)
        navRow.addView(monthLabel)
        navRow.addView(nextBtn)
        viewSchedule.addView(navRow)

        // Monthly stats
        val curMonthPrefix = "$calYear-${pad(calMonth + 1)}"
        val thisMonthAppts = all.filter { it.day.startsWith(curMonthPrefix) }
        val uniqueCustomers = thisMonthAppts.map { it.name.trim() }.distinct().size
        val statsTv = TextView(this)
        statsTv.text = "${thisMonthAppts.size} תורים החודש · $uniqueCustomers לקוחות שונים"
        statsTv.setTextColor(0xFF712B13.toInt())
        statsTv.textSize = 13f
        statsTv.gravity = android.view.Gravity.CENTER
        statsTv.setPadding(0, 8, 0, 16)
        viewSchedule.addView(statsTv)

        // Weekday header (Sun..Sat)
        val weekdayShort = arrayOf("א", "ב", "ג", "ד", "ה", "ו", "ש")
        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        for (w in weekdayShort) {
            val tv = TextView(this)
            tv.text = w
            tv.gravity = android.view.Gravity.CENTER
            tv.setTextColor(0xFF5F5E5A.toInt())
            tv.textSize = 12f
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            headerRow.addView(tv)
        }
        viewSchedule.addView(headerRow)

        // Appointment counts per day-of-month, for marking the grid
        val countsByDay = HashMap<Int, Int>()
        for (a in thisMonthAppts) {
            val d = a.day.split("-")[2].toInt()
            countsByDay[d] = (countsByDay[d] ?: 0) + 1
        }

        val firstOfMonth = Calendar.getInstance()
        firstOfMonth.set(calYear, calMonth, 1)
        val startDow = firstOfMonth.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday
        val daysInMonth = firstOfMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = todayStr()

        var dayCounter = 1 - startDow
        while (dayCounter <= daysInMonth) {
            val weekRow = LinearLayout(this)
            weekRow.orientation = LinearLayout.HORIZONTAL
            weekRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            for (col in 0..6) {
                val cellDay = dayCounter
                val cellParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                if (cellDay in 1..daysInMonth) {
                    val dateKey = "$calYear-${pad(calMonth + 1)}-${pad(cellDay)}"
                    val count = countsByDay[cellDay] ?: 0
                    val isToday = dateKey == todayKey
                    val isSelected = dateKey == selectedScheduleDay

                    val cell = LinearLayout(this)
                    cell.orientation = LinearLayout.VERTICAL
                    cell.gravity = android.view.Gravity.CENTER
                    cell.layoutParams = cellParams
                    cell.setPadding(2, 6, 2, 6)
                    cell.setBackgroundColor(
                        when {
                            isSelected -> 0xFF712B13.toInt()
                            isToday -> 0xFFEFE3DC.toInt()
                            else -> 0x00000000
                        }
                    )

                    val dayTv = TextView(this)
                    dayTv.text = "$cellDay"
                    dayTv.gravity = android.view.Gravity.CENTER
                    dayTv.textSize = 13f
                    dayTv.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF2C2C2A.toInt())
                    cell.addView(dayTv)

                    val hebDay = hebrewDateLabel(calYear, calMonth, cellDay, "d")
                    if (hebDay.isNotEmpty()) {
                        val hebTv = TextView(this)
                        hebTv.text = hebDay
                        hebTv.gravity = android.view.Gravity.CENTER
                        hebTv.textSize = 9f
                        hebTv.setTextColor(if (isSelected) 0xFFFAECE7.toInt() else 0xFF5F5E5A.toInt())
                        cell.addView(hebTv)
                    }

                    if (count > 0) {
                        val dot = TextView(this)
                        dot.text = "● $count"
                        dot.gravity = android.view.Gravity.CENTER
                        dot.textSize = 9f
                        dot.setTextColor(if (isSelected) 0xFFFAECE7.toInt() else 0xFF712B13.toInt())
                        cell.addView(dot)
                    }

                    cell.setOnClickListener {
                        selectedScheduleDay = if (selectedScheduleDay == dateKey) null else dateKey
                        renderSchedule()
                    }

                    weekRow.addView(cell)
                } else {
                    val empty = View(this)
                    empty.layoutParams = cellParams
                    weekRow.addView(empty)
                }
                dayCounter++
            }
            viewSchedule.addView(weekRow)
        }

        val divider = View(this)
        val dividerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        dividerParams.setMargins(0, 24, 0, 16)
        divider.layoutParams = dividerParams
        divider.setBackgroundColor(0xFFD3D1C7.toInt())
        viewSchedule.addView(divider)

        // List: either the selected day only, or everything in the viewed month
        val displayList = if (selectedScheduleDay != null) {
            all.filter { it.day == selectedScheduleDay }.sortedBy { it.time }
        } else {
            thisMonthAppts.sortedWith(compareBy({ it.day }, { it.time }))
        }

        if (displayList.isEmpty()) {
            val tv = TextView(this)
            tv.text = if (selectedScheduleDay != null) "אין תורים ביום זה" else "אין תורים בחודש זה"
            tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            tv.setTextColor(0xFF888780.toInt())
            tv.setPadding(0, 40, 0, 40)
            viewSchedule.addView(tv)
            return
        }

        val byDay = LinkedHashMap<String, MutableList<Appt>>()
        for (a in displayList) byDay.getOrPut(a.day) { mutableListOf() }.add(a)

        val weekdays = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")

        for ((day, appts) in byDay) {
            val dow = dayOfWeek(day) - 1
            val parts = day.split("-")
            val heb = hebrewDateLabel(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            val header = TextView(this)
            header.text = "יום ${weekdays[dow]} - ${parts[2]}/${parts[1]}/${parts[0]}" + if (heb.isNotEmpty()) " · $heb" else ""
            header.setTextColor(0xFF712B13.toInt())
            header.setTypeface(null, android.graphics.Typeface.BOLD)
            header.textSize = 13f
            header.setPadding(0, 16, 0, 8)
            viewSchedule.addView(header)

            for (a in appts) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setBackgroundColor(0xFFFFFFFF.toInt())
                row.setPadding(24, 20, 24, 20)
                val rowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                rowParams.setMargins(0, 0, 0, 12)
                row.layoutParams = rowParams

                val del = Button(this)
                del.text = "מחיקה"
                del.setTextColor(0xFFA32D2D.toInt())
                del.setBackgroundColor(0x00000000)
                del.setOnClickListener {
                    cancelReminder(a.id)
                    val updated = loadAppointments().filter { it.id != a.id }
                    saveAppointments(updated)
                    renderSchedule()
                }
                row.addView(del)

                val info = TextView(this)
                info.text = "${a.name}\n${a.service} (${a.mins} דק')"
                info.setTextColor(0xFF2C2C2A.toInt())
                val infoParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                info.layoutParams = infoParams
                row.addView(info)

                val time = TextView(this)
                time.text = a.time
                time.setTextColor(0xFF712B13.toInt())
                time.setTypeface(null, android.graphics.Typeface.BOLD)
                row.addView(time)

                viewSchedule.addView(row)
            }
        }
    }
}

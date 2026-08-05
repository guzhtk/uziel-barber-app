package com.uziel.barber

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Calendar

data class Appt(
    val id: Long,
    val name: String,
    val service: String,
    val mins: Int,
    val day: String,
    val time: String
)

class MainActivity : AppCompatActivity() {

    private val PREFS = "uziel_appointments_prefs"
    private val KEY = "appointments"
    private val CRASH_KEY = "last_crash"

    private lateinit var fname: EditText
    private lateinit var fservice: Spinner
    private lateinit var fday: Button
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

            fname = findViewById(R.id.fname)
            fservice = findViewById(R.id.fservice)
            fday = findViewById(R.id.fday)
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

            fday.setOnClickListener { showDatePicker() }

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

            renderSlots()
            updateSaveState()
        } catch (e: Throwable) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            saveCrash(sw.toString())
            showCrashScreen(sw.toString())
        }
    }

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

    private fun saveAppointments(list:

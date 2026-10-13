package app.everyday.android.calendarimport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.everyday.android.EverydayApplication
import app.everyday.android.data.ReminderRepository
import app.everyday.android.reminders.ReminderEngine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** The steps of importing from the phone's calendar (Phase 10). */
sealed interface ImportStep {
    data object Loading : ImportStep
    data class Calendars(val calendars: List<PhoneCalendar>) : ImportStep
    data class Events(val choices: List<ImportChoice>, val calendars: Map<Long, PhoneCalendar>) : ImportStep
    /** The imported events' calendar reminders, offered once. */
    data class Reminders(val offers: List<ReminderOffer>, val titles: Map<Long, String>, val allDay: Set<Long>, val imported: Int) : ImportStep
    data class Done(val imported: Int, val reminders: Int) : ImportStep
}

class ImportViewModel(
    private val reader: PhoneCalendarReader,
    private val repository: ReminderRepository,
    private val engine: ReminderEngine,
    private val requestSync: () -> Unit,
    /** The household the events go into, for the screen to say so. */
    val householdName: String?,
) : ViewModel() {
    var step by mutableStateOf<ImportStep>(ImportStep.Loading)
        private set
    var pickedCalendars by mutableStateOf(emptySet<Long>())
        private set
    var pickedEvents by mutableStateOf(emptySet<Long>())
        private set
    var pickedOffers by mutableStateOf(emptySet<ReminderOffer>())
        private set
    var error by mutableStateOf<String?>(null)

    /** Imported Everyday event ids per phone event, for adding reminders after. */
    private var importedIds: Map<Long, List<String>> = emptyMap()

    /** Called once calendar access is allowed. */
    fun loadCalendars() {
        if (step !is ImportStep.Loading) return
        viewModelScope.launch {
            val calendars = runCatching { reader.calendars() }.getOrElse { emptyList() }
            pickedCalendars = calendars.filter { it.primary }.map { it.id }.toSet()
            step = ImportStep.Calendars(calendars)
        }
    }

    fun toggleCalendar(id: Long) {
        pickedCalendars = if (id in pickedCalendars) pickedCalendars - id else pickedCalendars + id
    }

    fun loadEvents() {
        val calendars = (step as? ImportStep.Calendars)?.calendars ?: return
        step = ImportStep.Loading
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = System.currentTimeMillis()
            val to = today.plusMonths(IMPORT_MONTHS).atStartOfDay(zone).toInstant().toEpochMilli()
            val instances = reader.instances(pickedCalendars, from, to)
            val choices = ImportPlanner.plan(instances, today, zone, repository.importedKeys())
            pickedEvents = emptySet()
            step = ImportStep.Events(choices, calendars.associateBy { it.id })
        }
    }

    fun backToCalendars() {
        viewModelScope.launch { step = ImportStep.Calendars(reader.calendars()) }
    }

    fun toggleEvent(id: Long) {
        pickedEvents = if (id in pickedEvents) pickedEvents - id else pickedEvents + id
    }

    fun selectAll(all: Boolean) {
        val choices = (step as? ImportStep.Events)?.choices ?: return
        pickedEvents = if (all) choices.filter { it.items.isNotEmpty() }.map { it.eventId }.toSet() else emptySet()
    }

    fun import() {
        val choices = (step as? ImportStep.Events)?.choices?.filter { it.eventId in pickedEvents && it.items.isNotEmpty() } ?: return
        if (choices.isEmpty()) {
            error = "Tick the events to import."
            return
        }
        step = ImportStep.Loading
        viewModelScope.launch {
            val items = choices.flatMap { c -> c.items.map { c.eventId to it } }
            val ids = repository.importEvents(items.map { (_, item) -> item.sourceKey to item.draft })
            importedIds = items.zip(ids).groupBy({ it.first.first }, { it.second })
            engine.recompute()
            requestSync()
            val minutes = runCatching { reader.reminderMinutes(choices.map { it.eventId }) }.getOrElse { emptyMap() }
            val offers = ImportPlanner.offers(choices) { minutes[it].orEmpty() }
            pickedOffers = emptySet()
            step = if (offers.isEmpty()) {
                ImportStep.Done(ids.size, 0)
            } else {
                ImportStep.Reminders(
                    offers,
                    choices.associate { it.eventId to it.title },
                    choices.filter { it.allDay }.map { it.eventId }.toSet(),
                    ids.size,
                )
            }
        }
    }

    fun toggleOffer(offer: ReminderOffer) {
        if (offer.tooEarly) return
        pickedOffers = if (offer in pickedOffers) pickedOffers - offer else pickedOffers + offer
    }

    /** Adds the ticked reminders to every event imported from their calendar event; [skip] adds none. */
    fun finish(skip: Boolean) {
        val s = step as? ImportStep.Reminders ?: return
        val picked = if (skip) emptyList() else s.offers.filter { it in pickedOffers }
        step = ImportStep.Loading
        viewModelScope.launch {
            for (offer in picked) {
                for (eventId in importedIds[offer.eventId].orEmpty()) repository.addEventTime(eventId, offer.minute, offer.relative)
            }
            if (picked.isNotEmpty()) {
                engine.recompute()
                requestSync()
            }
            step = ImportStep.Done(s.imported, picked.size)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EverydayApplication
                ImportViewModel(
                    PhoneCalendarReader(app.contentResolver),
                    app.repository,
                    app.engine,
                    app.sync::requestSync,
                    app.session.current.householdName,
                )
            }
        }
    }
}

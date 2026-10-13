package io.github.traushb.actuali.services

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.traushb.actuali.models.Account
import io.github.traushb.actuali.models.AppearanceMode
import io.github.traushb.actuali.models.BudgetDisplayStyle
import io.github.traushb.actuali.models.BudgetMonth
import io.github.traushb.actuali.models.BudgetType
import io.github.traushb.actuali.models.Category
import io.github.traushb.actuali.models.CategoryBudget
import io.github.traushb.actuali.models.CategoryGroup
import io.github.traushb.actuali.models.CategoryProgressState
import io.github.traushb.actuali.models.CreditCardConfig
import io.github.traushb.actuali.models.CreditCardCycle
import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.NearbyPayee
import io.github.traushb.actuali.models.Payee
import io.github.traushb.actuali.models.PayeeLocation
import io.github.traushb.actuali.models.PayeeLocationSummary
import io.github.traushb.actuali.models.Rule
import io.github.traushb.actuali.models.ScheduleSummary
import io.github.traushb.actuali.models.StartTab
import io.github.traushb.actuali.models.Tag
import io.github.traushb.actuali.models.TagSummary
import io.github.traushb.actuali.models.Transaction
import io.github.traushb.actuali.models.TransactionDisplayMode
import io.github.traushb.actuali.models.TransactionStatusFilter
import io.github.traushb.actuali.models.UncategorizedTapAction
import io.github.traushb.actuali.services.banksync.BankSyncAccount
import io.github.traushb.actuali.services.database.Backup
import io.github.traushb.actuali.services.database.BackupService
import io.github.traushb.actuali.services.database.BudgetDatabase
import io.github.traushb.actuali.services.database.BudgetFileManager
import io.github.traushb.actuali.services.database.fetchCardAccountMappings
import io.github.traushb.actuali.services.database.fetchCategoryGroups
import io.github.traushb.actuali.services.database.fetchCommonPayees
import io.github.traushb.actuali.services.database.fetchNearbyPayees
import io.github.traushb.actuali.services.database.fetchPayeeLocations
import io.github.traushb.actuali.services.database.fetchPayees
import io.github.traushb.actuali.services.database.fetchPayeesWithLocations
import io.github.traushb.actuali.services.database.fetchBudgetMonth
import io.github.traushb.actuali.shared.WidgetSnapshot
import io.github.traushb.actuali.shared.WidgetSnapshotStore
import io.github.traushb.actuali.services.database.totalPaidIntoAccount
import io.github.traushb.actuali.services.diagnostics.DiagnosticLog
import io.github.traushb.actuali.services.network.ActualServerClient
import io.github.traushb.actuali.services.network.ActualServerError
import io.github.traushb.actuali.services.network.LoginMethod
import io.github.traushb.actuali.services.network.OpenIDAuthError
import io.github.traushb.actuali.services.network.OpenIDAuthenticator
import io.github.traushb.actuali.services.network.SimpleFINAccessKey
import io.github.traushb.actuali.services.network.SimpleFINClient
import io.github.traushb.actuali.services.network.SimpleFINCredentials
import io.github.traushb.actuali.services.notifications.CreditCardDueNotifier
import io.github.traushb.actuali.services.schedules.DayDate
import io.github.traushb.actuali.services.schedules.SchedulePoster
import io.github.traushb.actuali.services.schedules.ScheduleStatus
import io.github.traushb.actuali.services.sync.EncryptionKeyManager
import io.github.traushb.actuali.services.sync.SyncClient
import io.github.traushb.actuali.services.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

private val logger = Logger("BudgetStore")

/**
 * `@Published var x { didSet { … } }`: Compose-observable state with an
 * optional hook after every assignment (equal values included, as in Swift).
 */
class Published<T>(initial: T, private val didSet: (old: T, new: T) -> Unit = { _, _ -> }) : ReadWriteProperty<Any?, T> {
    private val state: MutableState<T> = mutableStateOf(initial)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val old = state.value
        state.value = value
        didSet(old, value)
    }
}

/** Pure rules for transaction batch edits and selection totals. */
object TransactionBulkEdit {
    /** The selection's net amount in cents (outflows negative). */
    fun total(transactions: List<Transaction>): Long = transactions.sumOf { it.amount }

    /**
     * Whether two transactions can be merged, following Actual's rules
     * (loot-core `validForMerge`): same account and amount. Transfers are left
     * out for now.
     */
    fun canMerge(a: Transaction, b: Transaction): Boolean =
        a.id != b.id && a.accountId == b.accountId && a.amount == b.amount &&
            a.transferId == null && a.transferAcct == null && b.transferId == null && b.transferAcct == null &&
            a.parentId == null && b.parentId == null

    /**
     * Which of two mergeable transactions survives, per Actual's
     * `determineKeepDrop`: the bank-imported one, then the one with an imported
     * payee, then the earlier one (the second on a tie).
     */
    fun keepAndDrop(a: Transaction, b: Transaction, importedIds: Set<String>): Pair<Transaction, Transaction> {
        val aImported = a.id in importedIds
        val bImported = b.id in importedIds
        if (bImported && !aImported) return b to a
        if (aImported && !bImported) return a to b
        val aPayee = !a.importedPayee.isNullOrEmpty()
        val bPayee = !b.importedPayee.isNullOrEmpty()
        if (bPayee && !aPayee) return b to a
        if (aPayee && !bPayee) return a to b
        return if (a.date < b.date) a to b else b to a
    }

    /**
     * The kept transaction after absorbing what the dropped one has and it
     * lacks (payee, category, notes), with cleared, reconciled and schedule
     * carried over if either had them. A split parent keeps no category.
     */
    fun merged(keep: Transaction, drop: Transaction): Transaction {
        var result = keep
        if (keep.payeeId == null) result = result.copy(payeeId = drop.payeeId, payeeName = drop.payeeName)
        if (keep.notes.isNullOrEmpty()) result = result.copy(notes = drop.notes)
        if (keep.categoryId == null && !keep.isParent) result = result.copy(categoryId = drop.categoryId, categoryName = drop.categoryName)
        return result.copy(
            cleared = keep.cleared || drop.cleared,
            reconciled = keep.reconciled || drop.reconciled,
            schedule = keep.schedule ?: drop.schedule,
        )
    }

    /** [notes] with `#tag` appended, or null when the note already carries the tag (compared case-insensitively, like Actual's tag filter). */
    fun notesAdding(tag: String, notes: String?): String? {
        val name = Tag.normalizeTagName(tag)
        if (!Tag.isValidTagName(name)) return null
        val existing = notes.orEmpty().trim()
        if (io.github.traushb.actuali.services.rules.TagFilter.notesContainTag(existing, "#$name", caseSensitive = false)) return null
        return if (existing.isEmpty()) "#$name" else "$existing #$name"
    }
}

/**
 * A user-configured HTTP header applied to every request to the Actual server,
 * for reverse proxies that guard it (e.g. Cloudflare Access service tokens:
 * `CF-Access-Client-Id` / `CF-Access-Client-Secret`). The `id` is UI-only.
 */
@Serializable
data class CustomHeader(val id: String = UUID.randomUUID().toString(), val name: String = "", val value: String = "")

/**
 * The single source of truth for app state, the Compose counterpart of the
 * iOS `@MainActor` ObservableObject: every property is snapshot state, every
 * method runs on the main thread, and I/O happens in the database, sync and
 * network services it calls. Feature areas live in the BudgetStore*.kt
 * extension files, as iOS splits them across `BudgetStore+*.swift`.
 */
class BudgetStore private constructor(
    internal val defaults: Defaults,
    preview: Boolean,
) {
    /** Unstructured work the store starts (Swift `Task {}`), on the main thread. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var categoryFundingTask: Job? = null

    fun enqueueCategoryFunding(savedTransactionId: String, defaults: Defaults = this.defaults) {
        val previousTask = categoryFundingTask
        categoryFundingTask = scope.launch {
            previousTask?.join()
            CategoryFundingAutomation.process(savedTransactionId, this@BudgetStore, defaults)
        }
    }

    // Published State

    /** Notified synchronously of the publications History diffs (iOS subscribes to the `@Published` projections). */
    internal var historyObserver: HistoryObserver? = null

    var isLoading by Published(false) { _, new -> historyObserver?.loadingChanged(new) }
    var isBudgetLoaded = false
        internal set
    var downloadingBudgetId: String? by Published(null)

    /** Global error alert for background/destructive operation failures; form-local errors stay in the presenting view. */
    var error: String? by Published(null)

    var serverURL: String by Published(if (preview) "" else defaults.string("serverURL") ?: "") { _, new -> defaults.set("serverURL", new) }
    var fallbackServerURL: String by Published(if (preview) "" else defaults.string("fallbackServerURL") ?: "") { _, new ->
        defaults.set("fallbackServerURL", new)
    }

    /**
     * Extra HTTP headers stamped onto every server request. Persisted in the
     * keychain because values may be secrets. Assigning re-persists and pushes
     * the live set to the network client.
     */
    var customHeaders: List<CustomHeader> by Published(emptyList()) { _, _ ->
        persistCustomHeaders()
        applyCustomHeadersToClient()
    }

    var isConnected by Published(false)

    /** Login methods advertised by the configured server (populated by [checkLoginMethods]). */
    var availableLoginMethods: List<LoginMethod> by Published(emptyList())

    /** Whether the server already has an account owner; when false, the first OpenID sign-in must supply the server password. */
    var ownerExists by Published(true)

    val supportsPasswordLogin: Boolean get() = availableLoginMethods.any { it.method == "password" }

    /** Whether password is the *active* login method — tapping Connect performs a direct password login. */
    val passwordLoginActive: Boolean get() = availableLoginMethods.any { it.method == "password" && it.isActive }

    val supportsOpenIDLogin: Boolean get() = availableLoginMethods.any { it.method == "openid" }

    /**
     * Whether the first OpenID sign-in must include the server password: the
     * server still has a password fallback and no owner exists yet. Mirrors the
     * web client's "Enter server password" prompt.
     */
    val requiresServerPassword: Boolean get() = supportsOpenIDLogin && supportsPasswordLogin && !ownerExists

    var currentBudgetId: String? by Published(if (preview) null else defaults.string("currentBudgetId")) { old, new ->
        defaults.set("currentBudgetId", new)
        if (new != old) {
            dismissTransactionImpactCues()
            budgetType = BudgetType.Envelope
            creditCardConfigs = emptyMap()
            loanConfigs = emptyMap()
            depositConfigs = emptyMap()
            cardAccountMappings = emptyMap()
            excludedFromSpentCategoryIds = loadExcludedFromSpentCategoryIds(new)
            perBudgetPrefsVersion += 1
        }
        historyObserver?.budgetIdChanged(new)
    }

    var remoteBudgets: List<RemoteBudget> by Published(emptyList())
    var accounts: List<Account> by Published(emptyList())
    var transactions: List<Transaction> by Published(emptyList()) { _, _ -> historyObserver?.transactionsPublished() }

    /** How many transactions still need a category (drives the Budget tab link to the uncategorized list). */
    var uncategorizedCount by Published(0)
    var categoryGroups: List<CategoryGroup> by Published(emptyList())
    var payees: List<Payee> by Published(emptyList())
    var tags: List<Tag> by Published(emptyList()) { _, new ->
        tagsByName = new.reversed().associateBy { it.tag.lowercase() }
    }
    var tagsByName: Map<String, Tag> = emptyMap()
        private set
    var tagSummaries: List<TagSummary> by Published(emptyList())
    var schedules: List<ScheduleSummary> by Published(emptyList())
    var schedulesLoaded by Published(false)
        internal set
    var scheduleLoadError: String? by Published(null)
        internal set
    var upcomingScheduledTransactionLength: String? by Published(null)
    var scheduleStatuses: Map<String, ScheduleStatus> by Published(emptyMap())
    var schedulePaymentDates: Map<String, Set<DayDate>> by Published(emptyMap())

    /** Statement dues (statement balance, payments since closing, remaining due) for active credit card accounts. */
    var creditCardStatementDues: Map<String, List<CreditCardCycle.StatementDue>> by Published(emptyMap())
    var currentBudgetMonth: BudgetMonth? by Published(null)
    var budgetType by Published(BudgetType.Envelope)
        internal set

    /**
     * Categories left out of the Spent (and so Saved/Net) summaries on the
     * Budget and Accounts tabs. Device- and budget-local: a presentation
     * preference, not a change to Actual's synced budget data.
     */
    var excludedFromSpentCategoryIds: Set<String> by Published(if (preview) emptySet() else loadExcludedFromSpentCategoryIds(defaults.string("currentBudgetId"))) { _, new ->
        currentBudgetId?.let { defaults.set(excludedFromSpentDefaultsKey(it), AppJson.encodeToString(ListSerializer(String.serializer()), new.toList())) }
    }

    /** Accounts wired up to a bank feed, refreshed alongside the rest of the budget. */
    var bankSyncAccounts: List<BankSyncAccount> by Published(emptyList())
        internal set
    internal var bankSyncLoadGeneration = 0

    /** Whether this device has claimed a SimpleFIN access key. */
    var isSimpleFINConfigured by Published(!preview && SimpleFINCredentials.isConfigured)
        internal set

    /** True for the length of a bank sync, so the UI can show progress and keep a second sync from starting. */
    var isBankSyncing by Published(false)
        internal set

    /**
     * The current calendar month's budget, tracked separately from
     * [currentBudgetMonth] (which follows the month the Budget tab is browsing)
     * so the widget never publishes historical balances.
     */
    var widgetBudgetMonth: BudgetMonth? = null
    internal var isPublishingReload = false
    internal var widgetSnapshotStore: WidgetSnapshotStore? = WidgetSnapshotStore.standard
    internal var lastWidgetSnapshot: Pair<File, WidgetSnapshot>? = null
    internal val creditCardDueNotifier = CreditCardDueNotifier()

    /**
     * Bumped every time the published data snapshot is republished (budget
     * load, local mutation, sync). Views that cache their own fetches key
     * reloads on this so changes made elsewhere reach them without a pull-down.
     */
    var dataVersion by Published(0)
        internal set

    /** Only views displaying sync status observe this, so status changes don't invalidate every budget view. */
    val syncStatus = SyncStatus()

    /**
     * True from the moment a budget is opened until its first sync attempt
     * finishes: until then the figures come from a snapshot that can trail the
     * server, and the UI says so (GH #126).
     */
    var isInitialSyncing by Published(false)
        internal set

    /** Whether we may WRITE payee_locations CRDT messages (server >= 26.4.0, probed via `GET /info`). */
    var payeeLocationWritesEnabled by Published(false)
        internal set

    var creditCardConfigs: Map<String, CreditCardConfig> by Published(emptyMap())
    var loanConfigs: Map<String, LoanConfig> by Published(emptyMap())
    var depositConfigs: Map<String, DepositConfig> by Published(emptyMap())

    /** Synced card-to-account mappings (keyword -> accountId). */
    var cardAccountMappings: Map<String, String> by Published(emptyMap())

    /** Currency code for formatting ("USD", "EUR", …); persisted, defaults to "USD". */
    var currencyCode: String by Published(if (preview) "USD" else defaults.string("currencyCode") ?: "USD") { old, new ->
        if (old == new) return@Published
        defaults.set("currencyCode", new)
        publishWidgetSnapshot()
    }

    /**
     * Follows Actual's synced `numberFormat` preference, with `comma-dot` as
     * the Actual default; deliberately no local fallback — the budget's synced
     * preference is the source of truth.
     */
    var numberFormat by Published(ActualNumberFormat.CommaDot) { old, new -> if (old != new) publishWidgetSnapshot() }

    /** Show just the narrow currency symbol ("$" instead of "NZ$"/"US$") (GH #83). */
    var useNarrowCurrencySymbol by persistedBool("useNarrowCurrencySymbol", false, preview) { publishWidgetSnapshot() }

    var appearanceMode: AppearanceMode by Published(if (preview) AppearanceMode.System else AppearanceMode.resolved(defaults.string("appearanceMode"))) { _, new ->
        defaults.set("appearanceMode", new.rawValue)
    }

    /** Tab the app opens on at launch; changes apply on the next launch. */
    var startTab: StartTab by Published(if (preview) StartTab.Accounts else StartTab.resolved(defaults.string(StartTab.DEFAULTS_KEY))) { _, new ->
        defaults.set(StartTab.DEFAULTS_KEY, new.rawValue)
    }

    /** How the Budget tab lays out its summary and category rows. */
    var budgetDisplayStyle: BudgetDisplayStyle by Published(
        if (preview) BudgetDisplayStyle.Clean else BudgetDisplayStyle.resolved(defaults.string("budgetDisplayStyle")),
    ) { _, new -> defaults.set("budgetDisplayStyle", new.rawValue) }

    var showCleanBudgetOverview by persistedBool("showCleanBudgetOverview", true, preview)
    var showCompactBudgetOverview by persistedBool("showCompactBudgetOverview", true, preview)

    /** Whether the Budget tab shows budgeted amounts, for people who use Actual to track rather than budget (GH #562). */
    var showBudgetedAmounts by persistedBool("showBudgetedAmounts", true, preview)

    /** Whether the Compact Budget style includes the Spent column (GH #452). */
    var showCompactSpentColumn by persistedBool("showCompactSpentColumn", DEFAULT_SHOW_COMPACT_SPENT_COLUMN, preview)

    var transactionDisplayMode: TransactionDisplayMode by Published(
        if (preview) TransactionDisplayMode.Flat else TransactionDisplayMode.resolved(defaults.string(TransactionDisplayMode.DEFAULTS_KEY)),
    ) { _, new -> defaults.set(TransactionDisplayMode.DEFAULTS_KEY, new.rawValue) }

    /** What tapping a row in the Uncategorized list opens. */
    var uncategorizedTapAction: UncategorizedTapAction by Published(
        if (preview) UncategorizedTapAction.CategoryPicker else UncategorizedTapAction.resolved(defaults.string(UncategorizedTapAction.DEFAULTS_KEY)),
    ) { _, new -> defaults.set(UncategorizedTapAction.DEFAULTS_KEY, new.rawValue) }

    var showBudgetProgressBars by persistedBool("showBudgetProgressBars", true, preview)

    /** Progress bars start full and decrease as category spending is used up. */
    var showInverseBudgetProgressBars by persistedBool("showInverseBudgetProgressBars", false, preview)

    /** Categories whose progress bar is hidden; device-local. */
    var hiddenBudgetProgressCategoryIDs: Set<String> by Published(if (preview) emptySet() else stringSet("hiddenBudgetProgressCategoryIDs")) { _, new ->
        defaults.set("hiddenBudgetProgressCategoryIDs", AppJson.encodeToString(ListSerializer(String.serializer()), new.sorted()))
    }
        private set

    fun setBudgetProgressBarHidden(hidden: Boolean, categoryId: String) {
        hiddenBudgetProgressCategoryIDs = if (hidden) hiddenBudgetProgressCategoryIDs + categoryId else hiddenBudgetProgressCategoryIDs - categoryId
    }

    var showCategoryStatusDots by persistedBool("showCategoryStatusDots", true, preview)

    /**
     * User-selected colors for category status dots and their progress bars,
     * as ARGB ints keyed by state. Unset states fall back to the state's tint.
     */
    private var categoryStatusDotColors: Map<String, Long> by Published(if (preview) emptyMap() else loadCategoryStatusDotColors()) { _, new ->
        if (new.isEmpty()) defaults.remove(CATEGORY_STATUS_DOT_COLORS_KEY)
        else defaults.set(CATEGORY_STATUS_DOT_COLORS_KEY, AppJson.encodeToString(MapSerializer(String.serializer(), Long.serializer()), new))
    }

    /** The custom ARGB color for [state], or null to use the state's own tint. */
    fun categoryStatusDotColor(state: CategoryProgressState): Long? = categoryStatusDotColors[state.name]

    fun setCategoryStatusDotColor(argb: Long, state: CategoryProgressState) {
        categoryStatusDotColors = categoryStatusDotColors + (state.name to argb)
    }

    fun hasCustomCategoryStatusDotColor(state: CategoryProgressState): Boolean = state.name in categoryStatusDotColors

    fun resetCategoryStatusDotColor(state: CategoryProgressState) {
        categoryStatusDotColors = categoryStatusDotColors - state.name
    }

    /** Whether Budget shows the status filter strip above the category list. */
    var showBudgetCheckInStrip by persistedBool("showBudgetCheckInStrip", true, preview)

    /** Whether the Compact style's group headers total their columns. */
    var showGroupTotals by persistedBool("showGroupTotals", true, preview)

    /** The cards of the balance impact popup, empty when it isn't showing. */
    var transactionImpactCues: List<TransactionImpactCue> by Published(emptyList())
    internal var impactDismissTask: Job? = null

    /** Whether changing a transaction shows the balance impact popup. */
    var showTransactionImpactCue by persistedBool("showTransactionImpactCue", true, preview) { if (!it) dismissTransactionImpactCues() }

    /** Whether the Budget tab shows a badge with the overspent-category count (GH #68). */
    var showOverspentBadge by persistedBool("showOverspentBadge", true, preview)

    /** Whether the Budget tab leaves out the Income group. */
    var hideIncomeGroup by persistedBool("hideIncomeGroup", false, preview)

    /** Whether amount fields accept conventional decimal entry instead of calculator-style. */
    var conventionalAmountEntry by persistedBool("conventionalAmountEntry", false, preview)

    /**
     * Whether monetary values are obscured wherever the app displays them —
     * a device-level privacy preference, not budget data.
     */
    var hideBalances by persistedBool("hideBalances", false, preview) {
        if (it) dismissTransactionImpactCues()
        publishWidgetSnapshot()
    }

    /** Whether shaking the device toggles [hideBalances] (opt-in). */
    var shakeToHideBalances by persistedBool("shakeToHideBalances", false, preview)

    /** Changes only for enabled shake gestures, so view feedback isn't coupled to other ways [hideBalances] changes. */
    var shakeFeedbackTrigger by Published(false)
        private set

    /** Whether displayed currency amounts omit their fractional digits; presentation only. */
    var hideDecimalPlaces by persistedBool("hideDecimalPlaces", false, preview) { publishWidgetSnapshot() }

    /** Whether Budget hides categories with no budget left this month. */
    var hideZeroBudgetCategories by persistedBool("hideZeroBudgetCategories", false, preview)

    /** Whether hidden categories and groups are included on the Budget tab. */
    var showHiddenCategories by persistedBool("showHiddenCategories", false, preview)

    /** The status preset selected by the transaction lists' chip strip (GH #439); shared by every list. */
    var transactionStatusFilter: TransactionStatusFilter by Published(
        if (preview) TransactionStatusFilter.All else TransactionStatusFilter.resolved(defaults.string(TransactionStatusFilter.DEFAULTS_KEY)),
    ) { _, new -> defaults.set(TransactionStatusFilter.DEFAULTS_KEY, new.rawValue) }

    /**
     * Whether the transaction lists show the status filter strip. Hiding the
     * strip drops any active filter with it: a chip that isn't visible can't be
     * tapped back to All.
     */
    var showTransactionStatusFilters by persistedBool(TransactionStatusFilter.STRIP_VISIBILITY_DEFAULTS_KEY, true, preview) {
        if (!it) transactionStatusFilter = TransactionStatusFilter.All
    }

    /** Whether the Accounts list drops its Closed Accounts section (GH #277). */
    var hideClosedAccounts by persistedBool("hideClosedAccounts", false, preview)

    /**
     * Categories the Budget list should show. With the hide toggle on, only
     * exactly-zero available drops out: overspent categories stay visible so
     * problems are never masked.
     */
    fun visibleCategoryBudgets(categories: List<CategoryBudget>, includeSpent: Boolean = false): List<CategoryBudget> {
        val visible = categories.filter { !it.isEffectivelyHidden }
        val filtered = if (hideZeroBudgetCategories && !includeSpent) visible.filter { it.available != 0L } else visible
        return if (showHiddenCategories) filtered + categories.filter { it.isEffectivelyHidden } else filtered
    }

    fun isCategoryIncludedInSpent(categoryId: String): Boolean = categoryId !in excludedFromSpentCategoryIds

    fun setCategoryIncludedInSpent(included: Boolean, categoryId: String) {
        excludedFromSpentCategoryIds = if (included) excludedFromSpentCategoryIds - categoryId else excludedFromSpentCategoryIds + categoryId
    }

    /**
     * Closed accounts the Accounts list should show — none when the hide toggle
     * is on. A view filter only: the All Accounts total still counts closed
     * accounts, so hiding them can't quietly change the net worth on screen.
     */
    val visibleClosedAccounts: List<Account> get() = if (hideClosedAccounts) emptyList() else accounts.filter { it.closed }

    /** Formats a standard currency amount unless the privacy mask is enabled. */
    fun displayBalance(cents: Long, locale: Locale = Locale.getDefault()): String {
        if (hideBalances) return HIDDEN_BALANCE_TEXT
        return if (hideDecimalPlaces) formatCurrencyWholeUnits(cents, locale) else formatCurrency(cents, locale)
    }

    /** [displayBalance] for reports that intentionally omit cents in their normal presentation. */
    fun displayBalanceWholeUnits(cents: Long, locale: Locale = Locale.getDefault()): String =
        if (hideBalances) HIDDEN_BALANCE_TEXT else formatCurrencyWholeUnits(cents, locale)

    /**
     * The clean row's "Spent" caption, from the signed net activity (negative
     * = spending). Spending keeps the familiar positive amount, but a net inflow
     * keeps a leading "+" so a category that only received deposits can't
     * masquerade as spending (GH #102).
     */
    fun displaySpentCaption(spentCents: Long): String {
        if (hideBalances) return HIDDEN_BALANCE_TEXT
        val magnitude = if (spentCents > 0) spentCents else -spentCents
        val text = if (hideDecimalPlaces) formatCurrencyWholeUnits(magnitude) else formatCurrency(magnitude)
        return if (spentCents > 0) "+$text" else text
    }

    /** Toggles [hideBalances] in response to a device shake gesture if enabled. */
    fun handleDeviceShake() {
        if (!shakeToHideBalances) return
        hideBalances = !hideBalances
        shakeFeedbackTrigger = !shakeFeedbackTrigger
    }

    /** Whether transaction saves record the payee's location (GH #24); off silences every recording path. */
    var recordPayeeLocations by persistedBool("recordPayeeLocations", true, preview)

    /** Transient toast text ("Posted N scheduled transactions"), cleared a few seconds after being set. */
    var schedulePostNotice: String? by Published(null)

    /** Count the Budget tab badge displays: the current month's overspent categories, or 0 when the badge is off. */
    val overspentBadgeCount: Int get() = if (showOverspentBadge) currentBudgetMonth?.overspentCount ?: 0 else 0

    // User Preferences (per-budget, stored locally)

    /** Read by the per-budget preference getters so assigning one recomposes its readers. */
    private var perBudgetPrefsVersion by mutableIntStateOf(0)

    private fun perBudgetString(prefix: String): String? {
        perBudgetPrefsVersion
        return currentBudgetId?.let { defaults.string("$prefix$it") }
    }

    private fun setPerBudgetString(prefix: String, value: String?) {
        val budgetId = currentBudgetId ?: return
        defaults.set("$prefix$budgetId", value)
        perBudgetPrefsVersion += 1
    }

    var defaultAccountId: String?
        get() = perBudgetString("defaultAccountId_")
        set(value) = setPerBudgetString("defaultAccountId_", value)

    /** Dashboard page the Reports tab opens on (GH #223); null means the first live page, like the web app. */
    var defaultDashboardPageId: String?
        get() = perBudgetString("defaultDashboardPageId_")
        set(value) = setPerBudgetString("defaultDashboardPageId_", value)

    /** Budget month last viewed in this budget; device-local, like the defaults above. */
    var lastViewedBudgetMonth: String?
        get() = perBudgetString("lastViewedBudgetMonth_")
        set(value) = setPerBudgetString("lastViewedBudgetMonth_", value)

    /** Sets keywords for an account and removes requested keywords in one batch write; a null account removes them. */
    suspend fun setCardAccountMappings(accountId: String?, keywords: List<String>, removingKeywords: List<String> = emptyList()) {
        val updated = cardAccountMappings.toMutableMap()
        for (key in removingKeywords + keywords) {
            updated.remove(key)
            updated.remove(key.trim())
        }
        if (!accountId.isNullOrEmpty()) {
            for (raw in keywords) raw.trim().takeIf { it.isNotEmpty() }?.let { updated[it] = accountId }
        }
        persistCardAccountMappings(updated)
    }

    /** Removes multiple card-to-account mappings in a single batch write. */
    suspend fun deleteCardAccountMappings(keywords: List<String>) {
        if (keywords.isEmpty()) return
        val updated = cardAccountMappings.toMutableMap()
        for (keyword in keywords) {
            updated.remove(keyword)
            updated.remove(keyword.trim())
        }
        persistCardAccountMappings(updated)
    }

    /** Publishes [updated] first, then persists it. A failed write rolls the published value back and surfaces the error. */
    private suspend fun persistCardAccountMappings(updated: Map<String, String>) {
        if (currentBudgetId == null || updated == cardAccountMappings) return
        val previous = cardAccountMappings
        cardAccountMappings = updated
        val syncClient = syncClient ?: run {
            cardAccountMappings = previous
            error = L("Card mappings need sync configured for this budget.")
            return
        }
        try {
            syncClient.setCardAccountMappings(updated, previous)
        } catch (e: Exception) {
            if (cardAccountMappings == updated) cardAccountMappings = previous
            error = e.localizedMessage
        }
    }

    /** accountId -> statement closing day (1...31); a configured account is a credit card with that billing cycle. */
    val creditCardStatementDays: Map<String, Int> get() = creditCardConfigs.mapValues { it.value.statementDay }

    /** accountId -> days between statement closing and payment due. */
    val creditCardDueOffsets: Map<String, Int> get() = creditCardConfigs.mapValues { it.value.dueOffsetDays }

    /** accountId -> credit limit in cents (positive); optional per card. */
    val creditCardLimits: Map<String, Long> get() = creditCardConfigs.mapNotNull { (id, config) -> config.limit?.let { id to it } }.toMap()

    /**
     * Statement days whose account still exists and is open — what the Credit
     * Cards screen lists and the Settings badge counts. Closed and deleted
     * accounts keep their stored config (reopening restores the cycle).
     */
    val activeCreditCardStatementDays: Map<String, Int>
        get() {
            val openAccountIds = accounts.filter { !it.closed }.mapTo(HashSet()) { it.id }
            return creditCardStatementDays.filterKeys { it in openAccountIds }
        }

    /** Writes a card's cycle config through sync; a null [statementDay] stops tracking the account. */
    suspend fun setCreditCard(
        accountId: String,
        statementDay: Int?,
        paymentDue: CreditCardCycle.PaymentDue = CreditCardCycle.PaymentDue.DaysAfter(CreditCardCycle.DEFAULT_DUE_OFFSET_DAYS),
        limit: Long?,
    ) {
        if (currentBudgetId == null) return
        val previous = creditCardConfigs[accountId]
        val config = statementDay?.let { day ->
            when (paymentDue) {
                is CreditCardCycle.PaymentDue.DaysAfter -> CreditCardConfig(day, paymentDue.days, null, limit)
                is CreditCardCycle.PaymentDue.DayOfMonth -> {
                    // Older builds ignore `dueDay` and fall back to
                    // `dueOffsetDays`; store the real gap so they stay close.
                    val cycle = CreditCardCycle(day, CreditCardCycle.PaymentDue.DayOfMonth(paymentDue.day))
                    val statement = cycle.previousStatementDate()
                    val offset = maxOf(1, statement.days(cycle.dueDate(statement)))
                    CreditCardConfig(day, offset, paymentDue.day, limit)
                }
            }
        }
        creditCardConfigs = creditCardConfigs.withEntry(accountId, config)
        val syncClient = syncClient
        if (syncClient == null) {
            creditCardConfigs = creditCardConfigs.withEntry(accountId, previous)
            error = L("Credit card settings need sync configured for this budget.")
            return
        }
        try {
            syncClient.setCreditCardConfig(accountId, config)
        } catch (e: Exception) {
            creditCardConfigs = creditCardConfigs.withEntry(accountId, previous)
            error = e.localizedMessage
        }
        loadCreditCardStatementDues()
        scheduleCreditCardDueNotifications()
    }

    /** Loans whose account still exists and is open — what the Loans screen lists. */
    val activeLoanConfigs: Map<String, LoanConfig>
        get() {
            val openAccountIds = accounts.filter { !it.closed }.mapTo(HashSet()) { it.id }
            return loanConfigs.filterKeys { it in openAccountIds }
        }

    /** The config to *display*: null unless it is a tracked loan whose account still exists and is open. */
    fun activeLoanConfig(accountId: String): LoanConfig? =
        accounts.firstOrNull { it.id == accountId }?.takeIf { !it.closed }?.let { loanConfigs[accountId] }

    /** Accounts already tracked as a card, loan or deposit; one account is one instrument. */
    val trackedAccountIds: Set<String> get() = creditCardConfigs.keys + loanConfigs.keys + depositConfigs.keys

    /** Writes a loan's config through sync; a null [config] stops tracking the account. */
    suspend fun setLoan(accountId: String, config: LoanConfig?) {
        if (currentBudgetId == null) return
        val previous = loanConfigs[accountId]
        loanConfigs = loanConfigs.withEntry(accountId, config)
        val syncClient = syncClient
        if (syncClient == null) {
            loanConfigs = loanConfigs.withEntry(accountId, previous)
            error = L("Loan settings need sync configured for this budget.")
            return
        }
        try {
            syncClient.setLoanConfig(accountId, config)
        } catch (e: Exception) {
            loanConfigs = loanConfigs.withEntry(accountId, previous)
            error = e.localizedMessage
        }
    }

    // Deposits

    /** Deposits whose account still exists and is open — what the Deposits screen lists. */
    val activeDepositConfigs: Map<String, DepositConfig>
        get() {
            val openAccountIds = accounts.filter { !it.closed }.mapTo(HashSet()) { it.id }
            return depositConfigs.filterKeys { it in openAccountIds }
        }

    /**
     * The config to *display*: null unless it is a tracked deposit whose
     * account still exists and is open. A matured deposit is still active.
     */
    fun activeDepositConfig(accountId: String): DepositConfig? =
        accounts.firstOrNull { it.id == accountId }?.takeIf { !it.closed }?.let { depositConfigs[accountId] }

    /** Writes a deposit's config through sync, optimistic with rollback; null stops tracking it. */
    suspend fun setDeposit(accountId: String, config: DepositConfig?) {
        if (currentBudgetId == null) return
        val previous = depositConfigs[accountId]
        depositConfigs = depositConfigs.withEntry(accountId, config)
        val syncClient = syncClient
        if (syncClient == null) {
            depositConfigs = depositConfigs.withEntry(accountId, previous)
            error = L("Deposit settings need sync configured for this budget.")
            return
        }
        try {
            syncClient.setDepositConfig(accountId, config)
        } catch (e: Exception) {
            depositConfigs = depositConfigs.withEntry(accountId, previous)
            error = e.localizedMessage
        }
    }

    /**
     * Everything paid into the loan so far, interest and fees included. null
     * when it can't be read rather than 0, so the row hides instead of claiming
     * nothing has been paid.
     */
    suspend fun totalPaidIntoLoan(accountId: String): Long? = runCatching { database?.totalPaidIntoAccount(accountId) }.getOrNull()

    fun creditCardCycle(accountId: String): CreditCardCycle? =
        creditCardConfigs[accountId]?.let { CreditCardCycle(it.statementDay, it.paymentDue) }

    /** The cycle to *display*: null unless it is a tracked card whose account still exists and is open. */
    fun activeCreditCardCycle(accountId: String): CreditCardCycle? =
        accounts.firstOrNull { it.id == accountId }?.takeIf { !it.closed }?.let { creditCardCycle(accountId) }

    /** Schedules or cancels credit card due reminders from the accounts, cycles, and the user's preference. */
    fun scheduleCreditCardDueNotifications() {
        val cycles = creditCardConfigs.keys.mapNotNull { id -> creditCardCycle(id)?.let { id to it } }.toMap()
        creditCardDueNotifier.scheduleNotifications(accounts, cycles, creditCardStatementDues, currencyCode, useNarrowCurrencySymbol)
    }

    /**
     * Credit still available on a tracked card: the limit less what is owed.
     * Actual holds a card's balance negative while money is owed, so the two
     * add. null unless the account is an active tracked card with a limit set.
     */
    fun availableCredit(accountId: String): Long? {
        val limit = creditCardLimits[accountId] ?: return null
        if (activeCreditCardCycle(accountId) == null) return null
        val account = accounts.firstOrNull { it.id == accountId } ?: return null
        return limit + account.balance
    }

    /**
     * Resolves an account id from a hint string (card digits, bank name, or
     * account name). Matching is deliberately conservative — a missed match
     * falls back to the default account or an error the user can act on, while
     * a wrong match logs money to the wrong account silently.
     */
    suspend fun resolveAccountId(hint: String): String? {
        var mappings = cardAccountMappings
        if (mappings.isEmpty()) {
            // A cold launch has no `database` yet, so open the budget file directly.
            val db = database ?: currentBudgetId?.takeIf(fileManager::budgetExists)?.let { runCatching { BudgetDatabase(fileManager.databasePath(it).path) }.getOrNull() }
            if (db != null) mappings = runCatching { db.fetchCardAccountMappings() }.getOrDefault(emptyMap())
        }
        return resolveAccountId(hint, accountsForIntent(), mappings)
    }

    // Private

    internal var serverClient = ActualServerClient()
    internal var diagnosticLog = DiagnosticLog.shared
    private var fileManagerOverride: BudgetFileManager? = null
    internal val fileManager: BudgetFileManager get() = fileManagerOverride ?: BudgetFileManager.shared

    /** Observable so screens reading [databaseForLogger] (Reports) re-run once a budget finishes opening or is switched. */
    internal var database: BudgetDatabase? by Published(null) { old, value ->
        // The cached poster holds the database; drop it whenever the
        // database identity changes so `database = null` before a
        // re-import actually closes the connection.
        if (value === old) return@Published
        dismissTransactionImpactCues()
        schedulePoster = null
        publishSchedules(Triple(emptyList(), emptyMap(), emptyMap()))
        schedulesLoaded = false
        scheduleLoadError = null
    }

    /**
     * Read-only accessor for collaborators that need direct DB access for
     * queries that don't fit the published cache. Writes go through store methods.
     */
    val databaseForLogger: BudgetDatabase? get() = database

    /** Nearby payees for the add-transaction form; every failure degrades to "no suggestions". */
    suspend fun fetchNearbyPayees(latitude: Double, longitude: Double): List<NearbyPayee> {
        val database = database ?: return emptyList()
        return try {
            database.fetchNearbyPayees(latitude, longitude)
        } catch (e: Exception) {
            logger.error("fetchNearbyPayees failed: ${e.message}")
            emptyList()
        }
    }

    /** Most frequently used payees from the last 12 weeks, for the payee picker. */
    suspend fun fetchCommonPayees(): List<Payee> {
        val database = database ?: return emptyList()
        return try {
            database.fetchCommonPayees()
        } catch (e: Exception) {
            logger.error("fetchCommonPayees failed: ${e.message}")
            emptyList()
        }
    }

    /** Tombstone one recorded payee location; false when the delete didn't stick. */
    suspend fun deletePayeeLocation(location: PayeeLocation): Boolean = deletePayeeLocations(listOf(location))

    /** Payees that have recorded locations, for the Payee Locations screen (GH #147). */
    suspend fun fetchPayeesWithLocations(): List<PayeeLocationSummary> {
        val database = database ?: return emptyList()
        return try {
            database.fetchPayeesWithLocations()
        } catch (e: Exception) {
            logger.error("fetchPayeesWithLocations failed: ${e.message}")
            emptyList()
        }
    }

    /** The recorded locations for one payee, newest first. */
    suspend fun fetchPayeeLocations(payeeId: String): List<PayeeLocation> {
        val database = database ?: return emptyList()
        return try {
            database.fetchPayeeLocations(payeeId)
        } catch (e: Exception) {
            logger.error("fetchPayeeLocations failed: ${e.message}")
            emptyList()
        }
    }

    /** Tombstone several recorded payee locations ("Clear All Locations"), behind the server-version gate. */
    suspend fun deletePayeeLocations(locations: List<PayeeLocation>): Boolean {
        val syncClient = syncClient
        if (!payeeLocationWritesEnabled || syncClient == null) return false
        return try {
            syncClient.deletePayeeLocations(locations)
            true
        } catch (e: Exception) {
            logger.error("deletePayeeLocations failed: ${e.message}")
            false
        }
    }

    /**
     * Ensure the saved budget is fully loaded — specifically that the sync
     * client is configured — before a write from outside the UI (a
     * notification tap or background work that can run before the launch load
     * finished).
     */
    suspend fun ensureBudgetReady() {
        if (syncClient != null) return
        loadTask?.let { task ->
            task.join()
            // A completed load that produced no database *failed*. Never cache
            // that failure for the process lifetime: fall through and retry.
            if (database != null) return
        }
        val budgetId = currentBudgetId ?: return
        if (!fileManager.budgetExists(budgetId)) return
        val task = scope.launch { loadLocalBudget(budgetId) }
        loadTask = task
        task.join()
    }

    /** Accounts, falling back to a direct database read while the published cache is still empty (cold launch). */
    suspend fun accountsForIntent(): List<Account> {
        if (accounts.isNotEmpty()) return accounts
        return try {
            openDatabaseForIntent()?.fetchAccounts().orEmpty()
        } catch (e: Exception) {
            logger.error("accountsForIntent DB fallback failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun categoriesForIntent(): List<Category> {
        if (categoryGroups.isNotEmpty()) return categoryGroups.flatMap { it.categories }
        return try {
            openDatabaseForIntent()?.fetchCategoryGroups().orEmpty().flatMap { it.categories }
        } catch (e: Exception) {
            logger.error("categoriesForIntent DB fallback failed: ${e.message}")
            emptyList()
        }
    }

    /** [id] if that category still exists, else null — so a deleted category is dropped instead of written as a dangling id. */
    suspend fun existingCategoryId(id: String?): String? = id?.takeIf { candidate -> categoriesForIntent().any { it.id == candidate } }

    suspend fun categoryBudgetForIntent(categoryId: String): CategoryBudget? {
        val currentMonth = currentMonthString()
        currentBudgetMonth?.takeIf { it.month == currentMonth }?.categoryBudgets?.firstOrNull { it.categoryId == categoryId }?.let { return it }
        return try {
            openDatabaseForIntent()?.fetchBudgetMonth(currentMonth)?.categoryBudgets?.firstOrNull { it.categoryId == categoryId }
        } catch (e: Exception) {
            logger.error("categoryBudgetForIntent DB fallback failed: ${e.message}")
            null
        }
    }

    suspend fun payeesForIntent(): List<Payee> {
        if (payees.isNotEmpty()) return payees
        return try {
            openDatabaseForIntent()?.fetchPayees().orEmpty()
        } catch (e: Exception) {
            logger.error("payeesForIntent DB fallback failed: ${e.message}")
            emptyList()
        }
    }

    private fun openDatabaseForIntent(): BudgetDatabase? =
        database ?: currentBudgetId?.takeIf(fileManager::budgetExists)?.let { BudgetDatabase(fileManager.databasePath(it).path) }

    /** Observable so screens keyed on it (the budget type picker) recompose once a download wires it up. */
    internal var syncClient: SyncClient? by Published(null)

    val canChangeBudgetType: Boolean get() = currentBudgetId != null && syncClient != null

    internal var syncStateJob: Job? = null

    // Backups

    var backups: List<Backup> by Published(emptyList())
        internal set

    /**
     * True while the user is viewing a restored backup — the revert baseline
     * exists. Taking a new backup consumes it, so the UI confirms first.
     */
    val isViewingBackup: Boolean get() = backups.any { it.isLatest }

    /**
     * True when metadata carries a cloudFileId but no groupId (a backup was
     * restored over a synced budget). Sync can't run again until the user
     * re-downloads the server copy.
     */
    var syncDetachedByRestore by Published(false)
        internal set

    private var backupServiceOverride: BackupService? = null
    internal val backupService: BackupService
        get() = backupServiceOverride ?: BackupService(fileManager).also { backupServiceOverride = it }

    /** Test-only: a file manager (and backup service) rooted somewhere disposable. */
    fun setFileManagerForTesting(manager: BudgetFileManager, backupService: BackupService) {
        fileManagerOverride = manager
        backupServiceOverride = backupService
    }

    /** Test-only: wire a database and sync client directly, without the file-system and server plumbing of [loadLocalBudget]. */
    fun configureForTesting(database: BudgetDatabase, syncClient: SyncClient) {
        this.database = database
        this.syncClient = syncClient
        subscribeToSyncState()
    }

    /**
     * Stop using [database] and [client]: let the client's in-flight push
     * finish or cancel, then close the connection. iOS gets this from
     * reference counting; the bundled SQLite driver needs an explicit close.
     */
    internal fun retire(database: BudgetDatabase?, client: SyncClient?) {
        if (database == null && client == null) return
        scope.launch {
            client?.cancelPendingSync()
            database?.close()
        }
    }

    /** The in-flight launch load, awaited by [ensureBudgetReady]. */
    internal var loadTask: Job? = null

    data class RemoteBudget(val id: String, val name: String, val groupId: String?, val isEncrypted: Boolean)

    // Bank sync plumbing (BudgetStoreBankSync.kt)

    internal var simpleFINClient = SimpleFINClient()
    internal var simpleFINAccessKeyProvider: () -> SimpleFINAccessKey? = { SimpleFINCredentials.accessKey }

    /** What the last bank sync did, for the accounts screen to show once. */
    var bankSyncSummary: String? by Published(null)

    /** Whether the server has its own SimpleFIN connection this device can use. */
    var serverProvidesBankSync by Published(false)
        internal set

    /** Test seam: runs once, between a bank sync's preparation and its write. */
    internal var bankSyncBeforeMaterializationHook: (() -> Unit)? = null

    /** Test seam: runs after the linked-account read, before its result is checked for staleness. */
    internal var bankSyncAccountsFetchedForTesting: (suspend () -> Unit)? = null

    // Scheduled posting (BudgetStoreSync.kt)

    /**
     * One poster held per database, NOT one per call: foreground syncs can run
     * concurrently, and the poster's double-post guard is per instance.
     * Cleared whenever the database identity changes.
     */
    internal var schedulePoster: SchedulePoster? = null
    internal var scheduleNoticeDismissTask: Job? = null

    // Budget months (BudgetStoreBudget.kt)

    internal var requestedBudgetMonth: String? = null
    internal var budgetMonthRequestGeneration = 0

    /** Whether the web's experimental goal templates are on for this budget (synced preference). */
    var goalTemplatesEnabled by Published(false)
        internal set

    /** Whether the web's goal templates editor UI is on (synced preference). */
    var goalTemplatesUIEnabled by Published(false)
        internal set

    // Rules (BudgetStoreRules.kt)

    var rules: List<Rule> by Published(emptyList())
        internal set
    var scheduleOwnedRuleIds: Set<String> by Published(emptySet())
        internal set
    var rulesSupported by Published(false)
        internal set

    // Initialization

    init {
        if (!preview) {
            // customHeaders assigns through the property: its hook also pushes
            // the headers onto the live network client.
            customHeaders = loadPersistedCustomHeaders()
            val token = loadAndMigrateAuthToken()
            val budgetId = currentBudgetId
            // The saved session is configured in the same task, before the
            // load, so the initial sync below is authenticated.
            if (budgetId != null && fileManager.budgetExists(budgetId)) {
                // Set before the task so the first render already knows the
                // numbers it is about to draw are provisional (GH #126).
                isInitialSyncing = true
                loadTask = scope.launch {
                    if (token != null) configureSavedSession(token)
                    loadLocalBudget(budgetId)
                    // On a cold launch the activity resumes before the load has
                    // wired the sync client, so the foreground sync no-ops.
                    // Sync here once the client exists.
                    syncOnForeground()
                    isInitialSyncing = false
                }
            } else if (token != null) {
                scope.launch { configureSavedSession(token) }
            }
        }
    }

    /** Configure server URL and token so sync works on launch and app resume. */
    private fun configureSavedSession(token: String) {
        applyCustomHeadersToClientNow()
        // Normalize here too: the field persists raw text per keystroke, and
        // only connect() normalizes.
        runCatching { serverClient.configure(serverURL, normalizedServerURL(fallbackServerURL)) }
        serverClient.setToken(token)
        isConnected = true
    }

    // Custom Headers

    /** Persisted headers from the keychain; best-effort, so a corrupt entry never blocks startup. */
    private fun loadPersistedCustomHeaders(): List<CustomHeader> =
        Keychain.get(CUSTOM_HEADERS_KEY)?.let { runCatching { AppJson.decodeFromString(ListSerializer(CustomHeader.serializer()), it) }.getOrNull() }.orEmpty()

    private fun persistCustomHeaders() {
        // Drop rows the user left completely blank so they don't accumulate.
        val meaningful = customHeaders.filter { it.name.isNotBlank() }
        if (meaningful.isEmpty()) {
            Keychain.remove(CUSTOM_HEADERS_KEY)
            return
        }
        Keychain.set(AppJson.encodeToString(ListSerializer(CustomHeader.serializer()), meaningful), CUSTOM_HEADERS_KEY)
    }

    /** The current header set as the network client takes it. Values never go to diagnostics. */
    private val currentCustomHeadersForClient: List<Pair<String, String>>
        get() = customHeaders.map { it.name.trim() to it.value.trim() }.filter { it.first.isNotEmpty() }

    private fun applyCustomHeadersToClient() = serverClient.setCustomHeaders(currentCustomHeadersForClient)

    /** Apply headers before configuring or probing a server, so the first request carries them. */
    internal fun applyCustomHeadersToClientNow() = serverClient.setCustomHeaders(currentCustomHeadersForClient)

    // Server Connection

    suspend fun connect() {
        val normalized = normalizedServerURL(serverURL)
        val normalizedFallback = normalizedServerURL(fallbackServerURL)
        if (normalized.isEmpty()) {
            error = L("Please enter a server URL")
            return
        }
        if (normalized != serverURL) serverURL = normalized
        if (normalizedFallback != fallbackServerURL) fallbackServerURL = normalizedFallback

        isLoading = true
        error = null
        try {
            applyCustomHeadersToClientNow()
            serverClient.configure(normalized, normalizedFallback)
        } catch (e: Exception) {
            error = e.localizedMessage
        }
        isLoading = false
    }

    /**
     * Reconfigures an authenticated session without logging out or touching
     * its downloaded budget. Publishes the new addresses only after both URLs
     * validate and the live client accepts them.
     */
    suspend fun updateServerConnection(newServerURL: String, newFallbackServerURL: String): Boolean {
        val normalized = normalizedServerURL(newServerURL)
        val normalizedFallback = normalizedServerURL(newFallbackServerURL)
        if (normalized.isEmpty()) {
            error = L("Please enter a server URL")
            return false
        }
        if (!isValidServerURL(normalized)) {
            error = ActualServerError.InvalidURL.message
            return false
        }
        if (normalizedFallback.isNotEmpty() && !isValidServerURL(normalizedFallback)) {
            error = ActualServerError.InvalidFallbackURL.message
            return false
        }

        isLoading = true
        error = null
        val previousServerURL = serverURL
        val previousFallbackServerURL = fallbackServerURL
        try {
            applyCustomHeadersToClientNow()
            if (normalized != previousServerURL) {
                // Probe the primary without fallback so an unreachable edit
                // can't be accepted merely because its alternate responds.
                serverClient.configure(normalized)
                try {
                    serverClient.fetchLoginMethods()
                } catch (probeError: ActualServerError) {
                    if (probeError.isConnectionFailure) {
                        // The replacement address can't be used. Restore the
                        // live client before leaving the saved connection alone.
                        runCatching { serverClient.configure(previousServerURL, previousFallbackServerURL) }
                        error = probeError.message
                        return false
                    }
                    // A server that answers but lacks this endpoint is
                    // reachable; older versions and route-stripping proxies are valid.
                }
            }
            serverClient.configure(normalized, normalizedFallback)
            serverURL = normalized
            fallbackServerURL = normalizedFallback
            refreshPayeeLocationSupport()
            return true
        } catch (e: Exception) {
            error = e.localizedMessage
            return false
        } finally {
            isLoading = false
        }
    }

    suspend fun login(password: String) {
        isLoading = true
        error = null
        try {
            val token = serverClient.login(password)
            Keychain.set(token, AUTH_TOKEN_KEY)
            isConnected = true
            fetchRemoteBudgets()
        } catch (e: Exception) {
            error = e.localizedMessage
            isConnected = false
        }
        isLoading = false
    }

    /**
     * Probe the configured server for its login methods so the UI can offer
     * password and/or OpenID sign-in. Best-effort: on failure we fall back to
     * password-only so the existing flow keeps working.
     */
    suspend fun checkLoginMethods() {
        try {
            availableLoginMethods = serverClient.fetchLoginMethods()
        } catch (e: ActualServerError.AuthProxyBlocked) {
            // Surface the actionable hint proactively rather than waiting for
            // the login attempt to fail with the same cryptic response.
            error = e.message
            availableLoginMethods = PASSWORD_ONLY_LOGIN_METHODS
        } catch (e: ActualServerError) {
            if (e.isConnectionFailure) {
                // Unreachable, so password login would fail identically. Say
                // why now, or tapping Connect looks like it did nothing at all.
                error = e.message
            } else {
                logger.error("Failed to fetch login methods: ${e.message}")
            }
            availableLoginMethods = PASSWORD_ONLY_LOGIN_METHODS
        } catch (e: Exception) {
            // Reachable, but the probe was unusable: older servers without the
            // endpoint, or a proxy stripping the route. Stay quiet.
            logger.error("Failed to fetch login methods: ${e.message}")
            availableLoginMethods = PASSWORD_ONLY_LOGIN_METHODS
        }
        // Only relevant when OpenID is offered; cheap enough to always refresh.
        if (supportsOpenIDLogin) ownerExists = serverClient.fetchOwnerCreated()
    }

    /**
     * Run the OpenID/OAuth browser sign-in end to end: ask the server for an
     * authorization URL, open it in a Custom Tab, then persist the returned
     * token exactly like a password login. [firstTimePassword] is only needed
     * when the server also has password auth and no users exist yet.
     */
    suspend fun loginWithOpenID(firstTimePassword: String?) {
        isLoading = true
        error = null
        try {
            val authURL = serverClient.beginOpenIDLogin(OpenIDAuthenticator.RETURN_URL, firstTimePassword)
            val token = OpenIDAuthenticator.authenticate(authURL)
            serverClient.setToken(token)
            Keychain.set(token, AUTH_TOKEN_KEY)
            isConnected = true
            fetchRemoteBudgets()
        } catch (e: OpenIDAuthError.Cancelled) {
            // The browser was dismissed — not an error worth surfacing.
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage
            isConnected = false
        }
        isLoading = false
    }

    /**
     * Clear the server session and, by default, wipe all local budget data.
     * Pass `false` to keep budget files on disk (the demo entry point clears the
     * session without destroying data; only an explicit Disconnect wipes it).
     */
    suspend fun logout(clearLocalData: Boolean = true) {
        syncClient?.cancelPendingSync()
        serverClient.setToken(null)
        diagnosticLog.clear()
        Keychain.remove(AUTH_TOKEN_KEY)
        // Close the open database and sync client BEFORE touching files: a
        // live sync client would let the next foreground refresh republish the
        // wiped budget's data from an orphaned connection.
        closeCurrentBudget()

        if (clearLocalData) {
            // Disconnecting should leave nothing behind, not just the auth
            // token. Encrypted budgets' keys go too: they must not outlive the
            // files they unlock.
            for (local in fileManager.listLocalBudgets()) {
                local.cloudFileId?.let { runCatching { EncryptionKeyManager.remove(it) } }
                runCatching { fileManager.deleteBudget(local.id) }
                forgetCachedCurrencyCode(local.id)
            }
            // The SimpleFIN access key is a bearer credential for live bank
            // data; disconnecting must not leave it reaching the accounts.
            runCatching { SimpleFINCredentials.clear() }
            isSimpleFINConfigured = false
        }

        isConnected = false
        remoteBudgets = emptyList()
        // Re-probe on the next connection in case the server URL changes.
        availableLoginMethods = emptyList()
        ownerExists = true
    }

    /**
     * Close whatever budget is open and return to the "select a budget" empty
     * state, leaving the server session alone. Tears down the database and
     * sync client first, then clears everything loaded in memory.
     */
    internal fun closeCurrentBudget() {
        syncStateJob?.cancel()
        syncStateJob = null
        retire(database, syncClient)
        syncClient = null
        database = null

        backups = emptyList()
        syncDetachedByRestore = false
        currentBudgetId = null
        requestedBudgetMonth = null
        currentBudgetMonth = null
        widgetBudgetMonth = null
        accounts = emptyList()
        transactions = emptyList()
        uncategorizedCount = 0
        categoryGroups = emptyList()
        payees = emptyList()
        tags = emptyList()
        tagSummaries = emptyList()
        schedulesLoaded = false
        scheduleLoadError = null
        syncStatus.state = io.github.traushb.actuali.services.sync.SyncState.Idle
        syncStatus.lastSyncTime = null
        // No budget left to catch up — the banner must not outlive the budget it described.
        isInitialSyncing = false
        dataVersion += 1
        clearWidgetSnapshot()
    }

    // Budget Deletion

    /**
     * Delete a budget's local copy — database, metadata, and backups — and any
     * device-side state for it, leaving the server file untouched so it can be
     * downloaded again. Deleting the open budget closes it.
     */
    suspend fun removeLocalBudget(cloudFileId: String) {
        // The derived encryption key must not outlive the budget, even without
        // a local directory (a failed download can leave a key behind).
        runCatching { EncryptionKeyManager.remove(cloudFileId) }
        val local = fileManager.listLocalBudgets().firstOrNull { it.cloudFileId == cloudFileId } ?: return
        if (local.id == currentBudgetId) {
            // Best-effort push of unsynced local edits before the database
            // holding them is destroyed; offline they're still lost, which the
            // confirmation dialog warns about.
            flushPendingSync()
            closeCurrentBudget()
        }
        runCatching { fileManager.deleteBudget(local.id) }
        forgetCachedCurrencyCode(local.id)
    }

    /**
     * Delete a budget's file on the Actual server — for every client — then
     * this device's copy and its list row. Returns null on success, or a
     * message so the confirmation sheet can stay open.
     */
    suspend fun deleteServerBudget(remoteBudget: RemoteBudget): String? {
        try {
            serverClient.deleteFile(remoteBudget.id)
        } catch (e: ActualServerError.FileNotFound) {
            // Already gone on the server — finish the local half so the stale row heals.
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.localizedMessage
        }
        removeLocalBudget(remoteBudget.id)
        remoteBudgets = remoteBudgets.filter { it.id != remoteBudget.id }
        return null
    }

    /** The auth token, migrating a legacy plain-preferences copy into the keychain. */
    private fun loadAndMigrateAuthToken(): String? {
        Keychain.get(AUTH_TOKEN_KEY)?.let { return it }
        val legacyToken = defaults.string(AUTH_TOKEN_KEY) ?: return null
        Keychain.set(legacyToken, AUTH_TOKEN_KEY)
        defaults.remove(AUTH_TOKEN_KEY)
        return legacyToken
    }

    // Currency cache

    private fun currencyCodeCacheKey(budgetId: String) = "currencyCode.$budgetId"

    internal fun cachedCurrencyCode(budgetId: String): String? = defaults.string(currencyCodeCacheKey(budgetId))

    /** Keeps an explicit empty value too: it is Actual's meaningful "None" preference. */
    internal fun cacheCurrencyCode(code: String, budgetId: String) = defaults.set(currencyCodeCacheKey(budgetId), code)

    internal fun forgetCachedCurrencyCode(budgetId: String) = defaults.remove(currencyCodeCacheKey(budgetId))

    /**
     * User-initiated currency changes (the Settings picker) go through here,
     * not a direct assignment: it also persists the choice into the budget's
     * own `preferences` table via sync (GH #59). Every DB load assigns
     * [currencyCode] directly, which keeps this from looping.
     */
    suspend fun setCurrencyCode(code: String) {
        currencyCode = code
        currentBudgetId?.let { cacheCurrencyCode(code, it) }
        val syncClient = syncClient ?: return
        try {
            syncClient.updateCurrencyCode(code)
        } catch (e: Exception) {
            error = e.localizedMessage
        }
    }

    /**
     * User-initiated budget type changes use Actual's synced `budgetType`
     * preference; refreshing afterwards switches reads between `zero_budgets`
     * and `reflect_budgets` immediately.
     */
    suspend fun setBudgetType(type: BudgetType) {
        val budgetId = currentBudgetId ?: return
        val database = database ?: return
        val syncClient = syncClient ?: return
        if (type == budgetType) return
        val previous = budgetType
        budgetType = type
        try {
            syncClient.setPreference("budgetType", type.rawValue)
            if (currentBudgetId != budgetId || this.database !== database) return
            refreshDataOnly()
        } catch (e: Exception) {
            if (currentBudgetId != budgetId || this.database !== database) return
            budgetType = previous
            // The preference and CRDT message can commit before saving the
            // sync clock fails. Re-read the database instead of assuming rollback.
            refreshDataOnly()
            if (currentBudgetId != budgetId || this.database !== database) return
            error = e.localizedMessage
        }
    }

    /** Number formatting changes go through the generic synced preference path. */
    suspend fun setNumberFormat(format: ActualNumberFormat) {
        numberFormat = format
        val syncClient = syncClient ?: return
        try {
            syncClient.setPreference("numberFormat", format.rawValue)
        } catch (e: Exception) {
            error = e.localizedMessage
        }
    }

    // Persistence helpers

    private fun persistedBool(key: String, default: Boolean, preview: Boolean, didSet: (Boolean) -> Unit = {}): Published<Boolean> {
        val initial = if (preview || !defaults.contains(key)) default else defaults.bool(key)
        return Published(initial) { _, new ->
            defaults.set(key, new)
            didSet(new)
        }
    }

    private fun stringSet(key: String): Set<String> =
        defaults.string(key)?.let { runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty().toSet()

    private fun excludedFromSpentDefaultsKey(budgetId: String) = "excludedFromSpentCategoryIds.$budgetId"

    private fun loadExcludedFromSpentCategoryIds(budgetId: String?): Set<String> = budgetId?.let { stringSet(excludedFromSpentDefaultsKey(it)) }.orEmpty()

    internal fun forgetExcludedFromSpent(budgetId: String) = defaults.remove(excludedFromSpentDefaultsKey(budgetId))

    private fun loadCategoryStatusDotColors(): Map<String, Long> =
        defaults.string(CATEGORY_STATUS_DOT_COLORS_KEY)?.let {
            runCatching { AppJson.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), it) }.getOrNull()
        }.orEmpty()

    internal fun currentMonthString(): String = LocalDate.now().let { "%04d-%02d".format(it.year, it.monthValue) }

    // Currency Formatting

    /** An amount in cents as a currency string in the budget's currency. */
    fun formatCurrency(cents: Long, locale: Locale = Locale.getDefault()): String =
        CurrencyAmountFormat.string(cents, currencyCode, useNarrowCurrencySymbol, numberFormat = numberFormat, locale = locale)

    /** Like [formatCurrency], but rounded to whole units, for compact chart annotations. */
    fun formatCurrencyWholeUnits(cents: Long, locale: Locale = Locale.getDefault()): String =
        CurrencyAmountFormat.string(cents, currencyCode, useNarrowCurrencySymbol, wholeUnits = true, numberFormat = numberFormat, locale = locale)

    companion object {
        /** The app's store, created on first use once ActualiApplication has installed the platform services. */
        val shared: BudgetStore by lazy { BudgetStore(Defaults.standard, preview = false) }

        /** A fresh store with no persisted state and no auto-load, for previews and tests. */
        fun previewInstance(defaults: Defaults = InMemoryDefaults()): BudgetStore = BudgetStore(defaults, preview = true)

        /** A store that restores its preferences from [defaults] but loads nothing, for preference tests. */
        fun loadingPersistedPreferences(defaults: Defaults): BudgetStore = BudgetStore(defaults, preview = false).also { it.loadTask?.cancel() }

        const val DEFAULT_SHOW_COMPACT_SPENT_COLUMN = true

        /**
         * One consistent-width replacement keeps masked amounts visually stable
         * while avoiding a numeric value in the UI.
         */
        const val HIDDEN_BALANCE_TEXT = "••••"

        internal const val AUTH_TOKEN_KEY = "authToken"
        private const val CUSTOM_HEADERS_KEY = "customHeaders"
        private const val CATEGORY_STATUS_DOT_COLORS_KEY = "categoryStatusDotColors"

        /** The login methods a server is assumed to offer when the probe can't tell. */
        private val PASSWORD_ONLY_LOGIN_METHODS = listOf(LoginMethod(method = "password", displayName = "Password", active = 1))

        /** Trims whitespace and prepends `https://` if the user omitted a scheme. Empty stays empty. */
        fun normalizedServerURL(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (Regex("^[A-Za-z][A-Za-z0-9+\\-.]*://").containsMatchIn(trimmed)) return trimmed
            return "https://$trimmed"
        }

        private fun isValidServerURL(raw: String): Boolean = runCatching { java.net.URI(raw) }.getOrNull()?.let { it.scheme != null && it.host != null } ?: false

        /**
         * Pure account resolution shared by the async path and the pending
         * imports list.
         */
        fun resolveAccountId(hint: String, accounts: List<Account>, cardMappings: Map<String, String>): String? {
            val trimmed = hint.trim().lowercase()
            if (trimmed.isEmpty()) return null
            val activeAccounts = accounts.filter { !it.closed }
            if (activeAccounts.isEmpty()) return null
            val activeIds = activeAccounts.mapTo(HashSet()) { it.id }

            // 1. Mapping keywords the hint contains. Longest key first so "1234"
            //    beats "12" and multi-match resolution is deterministic. Only
            //    hint-contains-key: the reverse would let a one-character hint
            //    match any keyword.
            val mappingsByLongestKey = cardMappings
                .map { it.key.trim().lowercase() to it.value }
                .filter { it.first.isNotEmpty() }
                .sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })
            mappingsByLongestKey.firstOrNull { (key, accountId) -> trimmed.contains(key) && accountId in activeIds }?.let { return it.second }

            // 2. Exact account name match.
            activeAccounts.firstOrNull { it.name.lowercase() == trimmed }?.let { return it.id }

            // 3. Whole-word name match ("Checking Account" hint -> "Checking"
            //    account), only when unambiguous: substring matching would send
            //    "HSBC cashback" to an account named "Cash".
            fun words(text: String) = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
            val hintWords = words(trimmed)
            val wordMatches = activeAccounts.filter { account ->
                val nameWords = words(account.name.lowercase())
                nameWords.isNotEmpty() && (hintWords.containsAll(nameWords) || nameWords.containsAll(hintWords))
            }
            return wordMatches.singleOrNull()?.id
        }
    }
}

/** A map with [key] set to [value], or removed when [value] is null. */
internal fun <K, V> Map<K, V>.withEntry(key: K, value: V?): Map<K, V> = if (value == null) this - key else this + (key to value)

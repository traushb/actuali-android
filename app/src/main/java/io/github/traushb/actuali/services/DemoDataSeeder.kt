package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.DepositConfig
import io.github.traushb.actuali.models.EntityNote
import io.github.traushb.actuali.models.LoanConfig
import io.github.traushb.actuali.models.PendingImport
import io.github.traushb.actuali.services.database.BudgetFileManager
import io.github.traushb.actuali.services.database.BudgetMetadata
import io.github.traushb.actuali.services.database.DatabaseQueue
import io.github.traushb.actuali.services.database.Db
import io.github.traushb.actuali.services.database.PreferenceKeys
import io.github.traushb.actuali.services.deposits.DepositGrowth
import io.github.traushb.actuali.services.loans.LoanAmortization
import io.github.traushb.actuali.services.schedules.DayDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private val logger = Logger("DemoData")

/**
 * Populates a local "demo" budget with curated data suitable for store
 * screenshots and for letting users explore the app without a server.
 * Overwrites any existing demo budget, and does NOT touch the server or sync.
 */
object DemoDataSeeder {
    const val BUDGET_ID = "demo"

    /**
     * Fixed so cleanup removes only the sample. Imports queued while the demo
     * is open also carry the demo's budget id, and those are real bank
     * messages the user can still adopt into another budget.
     */
    const val SAMPLE_PENDING_IMPORT_ID = "6d3c1f0e-8b5a-4e2d-9c47-1a2b3c4d5e6f"

    /**
     * Creates a local "demo" budget directory with a populated SQLite DB.
     * [tracking] seeds a tracking (`reflect_budgets`) budget rather than the
     * default envelope (`zero_budgets`) one. [seedUncategorized] adds one
     * on-budget uncategorized transaction — demo data is otherwise fully
     * categorized on-budget, so the Budget screen's uncategorized bar never
     * renders. [seedUnsupportedBankSync] links Chase Checking to GoCardless and
     * Ally Savings to Pluggy — providers Actuali can't refresh — so bank sync's
     * explanation can be exercised without a real bank link (GH #499).
     */
    fun seed(
        tracking: Boolean = false,
        seedUncategorized: Boolean = false,
        seedUnsupportedBankSync: Boolean = false,
        now: Long = System.currentTimeMillis(),
        fileManager: BudgetFileManager = BudgetFileManager.shared,
    ) {
        val budgetDir = fileManager.budgetDirectory(BUDGET_ID)
        // Remove any existing demo budget directory and recreate fresh
        budgetDir.deleteRecursively()
        budgetDir.mkdirs()

        val metadata = BudgetMetadata(id = BUDGET_ID, budgetName = if (tracking) "Demo Budget (Tracking)" else "Demo Budget")
        fileManager.metadataPath(BUDGET_ID).writeBytes(metadata.encoded())

        val dbPath = fileManager.databasePath(BUDGET_ID)
        DatabaseQueue(dbPath.path).use { queue ->
            queue.write { db ->
                createSchema(db, tracking)
                insertSeedData(db, tracking, seedUncategorized, now)
                if (seedUnsupportedBankSync) {
                    db.execute(
                        """
                        UPDATE accounts SET account_id = 'demo-' || id,
                            account_sync_source = CASE name
                                WHEN 'Chase Checking' THEN 'goCardless'
                                WHEN 'Ally Savings' THEN 'pluggyai'
                            END
                        WHERE name IN ('Chase Checking', 'Ally Savings')
                        """,
                    )
                }
            }
        }
        logger.info("Demo data seeded successfully at ${dbPath.path}")
    }

    /**
     * Seeds a sample pending import whose INR currency differs from the demo
     * budget's USD, so the currency mismatch review flow can be explored.
     */
    fun seedPendingImports(store: PendingImportStore = PendingImportStore.shared) {
        if (store.imports.any { it.id == SAMPLE_PENDING_IMPORT_ID }) return
        store.add(
            PendingImport(
                id = SAMPLE_PENDING_IMPORT_ID,
                originBudgetId = BUDGET_ID,
                amount = 156.00,
                sourceCurrencyCode = "INR",
                payee = "SWIGGY INST",
                cardHint = "Apple Card",
                rawText = "Paid INR 156.00 with Apple Card at SWIGGY INST",
            ),
        )
    }

    /** Removes the sample seeded by [seedPendingImports], leaving every other import alone. */
    fun removeSamplePendingImport(store: PendingImportStore = PendingImportStore.shared) {
        if (store.imports.none { it.id == SAMPLE_PENDING_IMPORT_ID }) return
        store.remove(SAMPLE_PENDING_IMPORT_ID)
    }

    // Schema

    private fun createSchema(db: Db, tracking: Boolean) {
        db.executeScript(
            """
            CREATE TABLE accounts (
                id TEXT PRIMARY KEY,
                name TEXT,
                type TEXT,
                offbudget INTEGER DEFAULT 0,
                closed INTEGER DEFAULT 0,
                tombstone INTEGER DEFAULT 0,
                sort_order REAL,
                account_id TEXT,
                account_sync_source TEXT,
                balance_current INTEGER,
                balance_available INTEGER,
                balance_limit INTEGER,
                mask TEXT,
                official_name TEXT,
                subtype TEXT,
                bank TEXT
            );
            CREATE TABLE transactions (
                id TEXT PRIMARY KEY,
                isParent INTEGER DEFAULT 0,
                isChild INTEGER DEFAULT 0,
                acct TEXT,
                category TEXT,
                amount INTEGER,
                description TEXT,
                notes TEXT,
                date INTEGER,
                financial_id TEXT,
                type TEXT,
                location TEXT,
                error TEXT,
                imported_description TEXT,
                starting_balance_flag INTEGER DEFAULT 0,
                transferred_id TEXT,
                sort_order REAL,
                tombstone INTEGER DEFAULT 0,
                cleared INTEGER DEFAULT 0,
                reconciled INTEGER DEFAULT 0,
                parent_id TEXT,
                schedule TEXT
            );
            CREATE TABLE categories (
                id TEXT PRIMARY KEY,
                name TEXT,
                is_income INTEGER DEFAULT 0,
                cat_group TEXT,
                sort_order REAL,
                tombstone INTEGER DEFAULT 0,
                hidden BOOLEAN NOT NULL DEFAULT 0
            );
            CREATE TABLE category_groups (
                id TEXT PRIMARY KEY,
                name TEXT UNIQUE,
                is_income INTEGER DEFAULT 0,
                sort_order REAL,
                tombstone INTEGER DEFAULT 0,
                hidden BOOLEAN NOT NULL DEFAULT 0
            );
            CREATE TABLE payees (
                id TEXT PRIMARY KEY,
                name TEXT,
                category TEXT,
                tombstone INTEGER DEFAULT 0,
                transfer_acct TEXT
            );
            CREATE TABLE payee_mapping (
                id TEXT PRIMARY KEY,
                targetId TEXT
            );
            CREATE TABLE category_mapping (
                id TEXT PRIMARY KEY,
                transferId TEXT
            );
            """,
        )
        // Envelope budgets live in zero_budgets, tracking budgets in
        // reflect_budgets. The columns are identical; only one table exists so
        // BudgetDatabase.budgetTable picks it without needing the preference.
        db.execute(
            """
            CREATE TABLE ${if (tracking) "reflect_budgets" else "zero_budgets"} (
                id TEXT PRIMARY KEY,
                month INTEGER,
                category TEXT,
                amount INTEGER DEFAULT 0,
                carryover INTEGER DEFAULT 0
            )
            """,
        )
        // Real Actual files key notes by the annotated row's own id (GH #131);
        // the category note UI hides itself as unsupported without the table.
        // Rules/schedules tables so Settings > Rules shows real rules and
        // Settings > Scheduled Transactions has rows. Column sets mirror the
        // app's reads (fetchRulesRanked, fetchSchedules) and writes
        // (ScheduleWriteBuilder, advanceScheduleNextDate). The dashboard tables
        // mirror BudgetDatabase's migrations, so the demo ships Reports dashboards.
        db.executeScript(
            """
            CREATE TABLE preferences (
                id TEXT PRIMARY KEY,
                value TEXT
            );
            CREATE TABLE rules (
                id TEXT PRIMARY KEY,
                stage TEXT,
                conditions_op TEXT,
                conditions TEXT,
                actions TEXT,
                tombstone INTEGER DEFAULT 0
            );
            CREATE TABLE schedules (
                id TEXT PRIMARY KEY,
                rule TEXT,
                name TEXT,
                posts_transaction INTEGER DEFAULT 0,
                custom_upcoming_length TEXT,
                completed INTEGER DEFAULT 0,
                tombstone INTEGER DEFAULT 0
            );
            CREATE TABLE schedules_next_date (
                id TEXT PRIMARY KEY,
                schedule_id TEXT,
                local_next_date INTEGER,
                local_next_date_ts INTEGER,
                base_next_date INTEGER,
                base_next_date_ts INTEGER
            );
            CREATE TABLE notes (
                id TEXT PRIMARY KEY,
                note TEXT
            );
            CREATE TABLE dashboard (
                id TEXT PRIMARY KEY,
                type TEXT,
                dashboard_page_id TEXT,
                x INTEGER DEFAULT 0,
                y INTEGER DEFAULT 0,
                width INTEGER DEFAULT 4,
                height INTEGER DEFAULT 2,
                meta TEXT,
                tombstone INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE dashboard_pages (
                id TEXT PRIMARY KEY,
                name TEXT,
                tombstone INTEGER DEFAULT 0
            );
            CREATE TABLE messages_crdt (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL UNIQUE,
                dataset TEXT NOT NULL,
                row TEXT NOT NULL,
                column TEXT NOT NULL,
                value BLOB NOT NULL
            );
            CREATE TABLE messages_clock (
                id INTEGER PRIMARY KEY,
                clock TEXT
            );
            CREATE TABLE db_version (
                version TEXT PRIMARY KEY
            );
            CREATE TABLE tags (
                id TEXT PRIMARY KEY,
                tag TEXT NOT NULL,
                color TEXT,
                description TEXT,
                tombstone INTEGER DEFAULT 0
            );
            CREATE TABLE __migrations__ (
                id INT PRIMARY KEY NOT NULL
            );
            """,
        )
    }

    // Seed Data

    private class SeedTransaction(
        val payee: String,
        val category: String?,
        val amount: Long,
        val date: Int,
        val account: String,
        val cleared: Boolean,
        val startingBalance: Boolean = false,
        val notes: String? = null,
        val id: String = uuid(),
        val transfer: String? = null,
    )

    private class MonthlyItem(val payee: String, val category: String?, val account: String, val day: Int, val amount: Long, val notes: String? = null)

    private fun uuid() = UUID.randomUUID().toString().uppercase()

    private fun json(value: Any?): JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to json(it.value) })
        is List<*> -> JsonArray(value.map(::json))
        else -> error("Unsupported JSON value $value")
    }

    private fun insertSeedData(db: Db, tracking: Boolean, seedUncategorized: Boolean, now: Long) {
        val nowDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val year = nowDate.year
        val month = nowDate.monthValue
        val today = nowDate.dayOfMonth
        val yyyymm = year * 100 + month

        // --- Accounts ---
        val chaseId = uuid()
        val allyId = uuid()
        val appleCardId = uuid()
        val vanguardId = uuid()
        val carLoanId = uuid()
        val cdId = uuid()

        insertAccount(db, chaseId, "Chase Checking", "checking", 0.0)
        insertAccount(db, allyId, "Ally Savings", "savings", 1.0)
        insertAccount(db, appleCardId, "Apple Card", "credit", 2.0)
        // Off-budget so it lifts net worth without distorting budget/spending reports.
        insertAccount(db, vanguardId, "Vanguard Brokerage", "investment", 3.0, offBudget = true)
        // Off-budget like Actual's docs recommend for loans, so the loan tracker
        // (and Record Payment, which needs an off-budget loan) works in the demo.
        insertAccount(db, carLoanId, "Toyota Auto Loan", "debt", 4.0, offBudget = true)
        // The deposit tracker's demo: a certificate of deposit, off-budget too.
        insertAccount(db, cdId, "Ally 12-Month CD", "savings", 5.0, offBudget = true)

        // --- Category groups + categories ---
        val incomeGroupId = uuid()
        insertCategoryGroup(db, incomeGroupId, "Income", isIncome = true, sortOrder = 0.0)
        val salaryId = uuid()
        insertCategory(db, salaryId, "Salary", incomeGroupId, isIncome = true, sortOrder = 0.0)
        // Actual books an on-budget account's opening balance to this income
        // category at account creation; mirror that so the demo's starting
        // balances don't sit in the uncategorized list.
        val startingBalancesCategoryId = uuid()
        insertCategory(db, startingBalancesCategoryId, "Starting Balances", incomeGroupId, isIncome = true, sortOrder = 1.0)

        val essentialsId = uuid()
        insertCategoryGroup(db, essentialsId, "Essentials", isIncome = false, sortOrder = 1.0)
        val groceriesId = uuid()
        val rentId = uuid()
        val utilitiesId = uuid()
        val internetId = uuid()
        insertCategory(db, groceriesId, "Groceries", essentialsId, isIncome = false, sortOrder = 0.0)
        insertCategory(db, rentId, "Rent", essentialsId, isIncome = false, sortOrder = 1.0)
        insertCategory(db, utilitiesId, "Utilities", essentialsId, isIncome = false, sortOrder = 2.0)
        insertCategory(db, internetId, "Internet", essentialsId, isIncome = false, sortOrder = 3.0)

        val transportId = uuid()
        insertCategoryGroup(db, transportId, "Transport", isIncome = false, sortOrder = 2.0)
        val fuelId = uuid()
        val transitId = uuid()
        val parkingId = uuid()
        insertCategory(db, fuelId, "Fuel", transportId, isIncome = false, sortOrder = 0.0)
        insertCategory(db, transitId, "Transit", transportId, isIncome = false, sortOrder = 1.0)
        insertCategory(db, parkingId, "Parking", transportId, isIncome = false, sortOrder = 2.0)
        // Paired with the auto loan: its payments are budgeted here.
        val carPaymentId = uuid()
        insertCategory(db, carPaymentId, "Car Payment", transportId, isIncome = false, sortOrder = 3.0)

        val lifestyleId = uuid()
        insertCategoryGroup(db, lifestyleId, "Lifestyle", isIncome = false, sortOrder = 3.0)
        val diningId = uuid()
        val coffeeId = uuid()
        val entertainmentId = uuid()
        val shoppingId = uuid()
        insertCategory(db, diningId, "Dining Out", lifestyleId, isIncome = false, sortOrder = 0.0)
        insertCategory(db, coffeeId, "Coffee", lifestyleId, isIncome = false, sortOrder = 1.0)
        insertCategory(db, entertainmentId, "Entertainment", lifestyleId, isIncome = false, sortOrder = 2.0)
        insertCategory(db, shoppingId, "Shopping", lifestyleId, isIncome = false, sortOrder = 3.0)

        val healthId = uuid()
        insertCategoryGroup(db, healthId, "Health & Wellness", isIncome = false, sortOrder = 4.0)
        val gymId = uuid()
        val pharmacyId = uuid()
        insertCategory(db, gymId, "Gym", healthId, isIncome = false, sortOrder = 0.0)
        insertCategory(db, pharmacyId, "Pharmacy", healthId, isIncome = false, sortOrder = 1.0)

        // --- Notes ---
        // One category arrives annotated so the category note (GH #131) is
        // visible in the demo budget rather than only after the user writes one.
        insertNote(db, groceriesId, "Target \$650/mo. Household supplies count here; takeaway goes to Dining Out.")
        // A second note carries a markdown link so tappable note links (GH #190)
        // are demonstrable — and UI-testable — in the demo budget.
        insertNote(db, diningId, "Cap \$250/mo.\n[Rewards portal](https://example.com/rewards)")
        // One account arrives annotated so the account note (GH #198) shows.
        // The "account-" prefix is the key Actual itself uses for account notes.
        insertNote(db, EntityNote.accountNoteId(chaseId), "Direct deposit lands here on the 15th; keep a \$500 buffer for the card autopay.")
        // The paired loan category carries a template so the loan section's
        // Payment Target row has a figure to show.
        insertNote(db, carPaymentId, "#template 425")

        // --- Payees ---
        val wholeFoodsId = uuid()
        val traderJoesId = uuid()
        val landlordId = uuid()
        val pgeId = uuid()
        val comcastId = uuid()
        val shellId = uuid()
        val bartId = uuid()
        val chipotleId = uuid()
        val blueBottleId = uuid()
        val netflixId = uuid()
        val amazonId = uuid()
        val fitnessId = uuid()
        val cvsId = uuid()
        val paycheckId = uuid()
        val startingBalanceId = uuid()
        val vanguardPayeeId = uuid()
        val marketId = uuid()
        val interestId = uuid()

        for ((id, name) in listOf(
            wholeFoodsId to "Whole Foods", traderJoesId to "Trader Joe's", landlordId to "Landlord Properties LLC",
            pgeId to "PG&E", comcastId to "Comcast", shellId to "Shell", bartId to "BART", chipotleId to "Chipotle",
            blueBottleId to "Blue Bottle Coffee", netflixId to "Netflix", amazonId to "Amazon", fitnessId to "24 Hour Fitness",
            cvsId to "CVS Pharmacy", paycheckId to "Paycheck", startingBalanceId to "Starting Balance", vanguardPayeeId to "Vanguard",
            marketId to "Market Gain", interestId to LoanConfig.INTEREST_PAYEE_NAME,
        )) {
            insertPayee(db, id, name)
        }

        // Transfer payees — one per account, name-less with transfer_acct set,
        // exactly as Actual creates them alongside each account. The transfer
        // flows resolve each leg's payee through these.
        val transferPayeeIds = mutableMapOf<String, String>()
        for (accountId in listOf(chaseId, allyId, appleCardId, vanguardId, carLoanId, cdId)) {
            val payeeId = uuid()
            transferPayeeIds[accountId] = payeeId
            insertPayee(db, payeeId, null, transferAccountId = accountId)
        }

        // --- Tags ---
        insertTag(db, "demo-tag-vacation", "vacation", "#3b82f6", "Vacation and travel expenses")
        insertTag(db, "demo-tag-reimbursable", "reimbursable", "#10b981", "Work expenses to submit for reimbursement")
        insertTag(db, "demo-tag-tax-deductible", "tax-deductible", "#f59e0b", "Items for tax deduction")
        insertTag(db, "demo-tag-coffee", "coffee", "#8b5cf6", "Coffee shops and cafes")
        insertTag(db, "demo-tag-refund", "refund", "#ec4899", "Refunds and returns")

        // --- Transactions ---
        // ~6 full months of history plus the current month-to-date so the
        // Reports (net worth, cash flow, spending vs. average) have real trends.
        val historyMonths = 6

        /** YYYYMMDD for [day] (clamped to 1...28) of the month [monthsAgo] before the current one. */
        fun ymd(monthsAgo: Int, day: Int): Int {
            val date = nowDate.minusMonths(monthsAgo.toLong()).withDayOfMonth(day.coerceIn(1, 28))
            return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        }

        val transactions = mutableListOf<SeedTransaction>()

        // Starting balances ~historyMonths months ago, before the recurring
        // flow. On-budget ones carry the Starting Balances income category
        // (Actual's behavior); the off-budget brokerage takes none.
        val openDate = ymd(historyMonths, 1)
        // A $22,000 car loan at 6% paid $425/mo (about five and a half years),
        // and a $10,000 12-month CD at 4.5% compounding quarterly, both opened
        // with the rest of the history.
        val loanConfig = LoanConfig(
            originalBalance = 2_200_000,
            annualRatePercent = 6.0,
            minimumPayment = 42500,
            escrowOrFees = null,
            categoryId = carPaymentId,
        )
        val depositConfig = DepositConfig(
            kind = DepositConfig.Kind.Fixed,
            amount = 1_000_000,
            annualRatePercent = 4.5,
            compounding = DepositConfig.Compounding.Quarterly,
            openedOn = DayDate.fromYYYYMMDD(openDate)!!,
            termMonths = 12,
        )
        transactions += SeedTransaction(startingBalanceId, startingBalancesCategoryId, 1_050_000, openDate, allyId, true, startingBalance = true)
        transactions += SeedTransaction(startingBalanceId, startingBalancesCategoryId, 280_000, openDate, chaseId, true, startingBalance = true)
        transactions += SeedTransaction(startingBalanceId, null, 4_200_000, openDate, vanguardId, true, startingBalance = true)
        transactions += SeedTransaction(startingBalanceId, null, -loanConfig.originalBalance, openDate, carLoanId, true, startingBalance = true)
        transactions += SeedTransaction(startingBalanceId, null, depositConfig.amount, openDate, cdId, true, startingBalance = true)

        // Per-month spending template; slight per-month variation is applied
        // deterministically below.
        val monthly = listOf(
            // Income (positive)
            MonthlyItem(paycheckId, salaryId, chaseId, 1, 320_000),
            MonthlyItem(paycheckId, salaryId, chaseId, 15, 320_000),
            // Essentials
            MonthlyItem(landlordId, rentId, chaseId, 1, -185_000),
            MonthlyItem(pgeId, utilitiesId, chaseId, 7, -8500),
            MonthlyItem(comcastId, internetId, chaseId, 6, -7000),
            MonthlyItem(wholeFoodsId, groceriesId, chaseId, 3, -8750),
            MonthlyItem(traderJoesId, groceriesId, chaseId, 11, -5200),
            MonthlyItem(wholeFoodsId, groceriesId, chaseId, 19, -9600),
            MonthlyItem(traderJoesId, groceriesId, chaseId, 26, -6300),
            // Transport
            MonthlyItem(shellId, fuelId, chaseId, 9, -5500),
            MonthlyItem(shellId, fuelId, chaseId, 23, -6000, "Road trip gas #vacation"),
            MonthlyItem(bartId, transitId, chaseId, 4, -2500),
            MonthlyItem(bartId, transitId, chaseId, 18, -2500),
            // Lifestyle (mostly Apple Card)
            MonthlyItem(chipotleId, diningId, appleCardId, 5, -1450),
            MonthlyItem(chipotleId, diningId, appleCardId, 16, -2500, "Team lunch #reimbursable"),
            MonthlyItem(chipotleId, diningId, appleCardId, 24, -1725),
            MonthlyItem(blueBottleId, coffeeId, appleCardId, 2, -575, "Morning latte #coffee"),
            MonthlyItem(blueBottleId, coffeeId, appleCardId, 8, -650),
            MonthlyItem(blueBottleId, coffeeId, appleCardId, 14, -700, "Client coffee chat #coffee #reimbursable"),
            MonthlyItem(blueBottleId, coffeeId, appleCardId, 21, -550),
            MonthlyItem(netflixId, entertainmentId, appleCardId, 11, -2299),
            MonthlyItem(amazonId, shoppingId, appleCardId, 6, -4599, "Desk equipment #tax-deductible"),
            MonthlyItem(amazonId, shoppingId, appleCardId, 20, -3199),
            MonthlyItem(amazonId, shoppingId, appleCardId, 25, 2999, "Returned item #refund"),
            // Health & wellness
            MonthlyItem(fitnessId, gymId, chaseId, 10, -3500),
            MonthlyItem(cvsId, pharmacyId, chaseId, 13, -1850),
            // Off-budget: monthly brokerage contribution
            MonthlyItem(vanguardPayeeId, null, vanguardId, 2, 50000),
        )
        val pendingDayByAccount = monthly.filter { it.day <= today }.groupBy { it.account }.mapValues { (_, items) -> items.maxOf { it.day } }

        for (monthsAgo in historyMonths downTo 0) {
            // Deterministic per-month wiggle so months aren't identical.
            val wiggle = listOf(0, 7, -4, 11, -8, 5, 3)[minOf(monthsAgo, 6)]
            for (item in monthly) {
                // Current month: only include entries up to today (month-to-date).
                if (monthsAgo == 0 && item.day > today) continue
                val varied = if (item.amount < 0) {
                    // Vary discretionary spend by a few percent; keep fixed bills exact.
                    val isFixed = item.category == rentId || item.category == internetId || item.category == gymId || item.category == entertainmentId
                    if (isFixed) item.amount else item.amount + (item.amount / 100) * wiggle
                } else {
                    item.amount
                }
                // Older transactions are cleared; each account's newest are still pending.
                val cleared = monthsAgo != 0 || item.day < (pendingDayByAccount[item.account] ?: item.day)
                transactions += SeedTransaction(item.payee, item.category, varied, ymd(monthsAgo, item.day), item.account, cleared, notes = item.notes)
            }

            // Quarterly market gains on the brokerage account, so net worth trends up.
            if (monthsAgo % 3 == 0) {
                transactions += SeedTransaction(marketId, null, 95000L + 5000L * (historyMonths - monthsAgo), ymd(monthsAgo, 28), vanguardId, true)
            }
        }

        // An on-budget expense with no category — the off-budget brokerage
        // rows don't count (uncategorizedAccountConditions), so without this
        // the demo budget's uncategorized count is always 0.
        if (seedUncategorized) {
            transactions += SeedTransaction(wholeFoodsId, null, -2500, ymd(0, today), chaseId, false)
        }

        // Auto loan: a payment on the 5th of every month after it opened, split
        // the way Record Payment posts one — the lender's interest charge on
        // the loan, then a transfer from checking carrying Car Payment on its
        // on-budget leg. Interest comes from the same engine the loan screens
        // project with, so the register and the payoff figures agree.
        var owed = loanConfig.originalBalance
        for (monthsAgo in historyMonths - 1 downTo 0) {
            if (monthsAgo == 0 && today < 5) continue
            val date = ymd(monthsAgo, 5)
            val interest = LoanAmortization.monthlyInterest(owed, loanConfig.annualRatePercent)
            owed += interest - loanConfig.minimumPayment
            val sourceId = uuid()
            val targetId = uuid()
            transactions += SeedTransaction(interestId, null, -interest, date, carLoanId, true)
            transactions += SeedTransaction(transferPayeeIds.getValue(carLoanId), carPaymentId, -loanConfig.minimumPayment, date, chaseId, true, id = sourceId, transfer = targetId)
            transactions += SeedTransaction(transferPayeeIds.getValue(chaseId), null, loanConfig.minimumPayment, date, carLoanId, true, id = targetId, transfer = sourceId)
        }

        // CD: interest credited at the close of each quarter so far, the same
        // stepped growth the deposit screens chart.
        for (quarter in 1..depositConfig.termMonths / 3) {
            val credited = DayDate.fromYYYYMMDD(openDate)!!.addingMonths(quarter * 3)
            if (credited.yyyymmdd > year * 10000 + month * 100 + today) break
            val interest = DepositGrowth.value(depositConfig, quarter * 3) - DepositGrowth.value(depositConfig, (quarter - 1) * 3)
            transactions += SeedTransaction(interestId, null, interest, credited.yyyymmdd, cdId, true)
        }

        // Newest first, so sort_order (descending) matches date order.
        transactions.sortByDescending { it.date }
        var sortOrder = System.currentTimeMillis().toDouble()
        for (t in transactions) {
            insertTransaction(db, t, sortOrder)
            sortOrder -= 1
        }

        /** Next occurrence of a monthly-on-[day] schedule, strictly after today. */
        fun nextMonthly(day: Int): Int {
            if (today < day) return yyyymm * 100 + day
            val date = nowDate.plusMonths(1).withDayOfMonth(day)
            return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        }

        // The pattern day must match the stored next date's day of month, or
        // the recurrence advances to the wrong day after the first post
        // (nextOccurrence is built from the patterns). Stored as an object, as
        // Actual stores recurrences — the iOS seeder nests it as a string,
        // which reads back as an unsupported date condition.
        fun scheduleDate(next: Int): JsonElement = json(
            mapOf(
                "frequency" to "monthly",
                "interval" to 1,
                "start" to DayDate.fromYYYYMMDD(next)!!.iso,
                "patterns" to listOf(mapOf("type" to "day", "value" to next % 100)),
                "skipWeekend" to false,
                "weekendSolveMode" to "after",
                "endMode" to "never",
            ),
        )

        // --- Rules and schedules ---
        // A schedule is a rules row (conditions + link-schedule action) plus a
        // schedules row plus a next-date row, matching
        // ScheduleWriteBuilder.createPlan's shape. Both next dates are in the
        // future so the auto-poster never fires on a fresh demo load.

        // A standalone categorization rule — the canonical Actual demo rule:
        // any new Shell transaction lands in Fuel.
        insertRule(
            db, uuid(),
            listOf(mapOf("op" to "is", "field" to "payee", "value" to shellId)),
            listOf(mapOf("op" to "set", "field" to "category", "value" to fuelId)),
        )

        // Rent and Netflix, due on the 1st and 11th of the next month.
        for ((name, payee, account, category, amount, day) in listOf(
            ScheduleSeed("Rent", landlordId, chaseId, rentId, -185_000, 1),
            ScheduleSeed("Netflix", netflixId, appleCardId, entertainmentId, -2299, 11),
        )) {
            val scheduleId = uuid()
            val ruleId = uuid()
            val next = nextMonthly(day)
            insertRule(
                db, ruleId,
                listOf(
                    mapOf("op" to "is", "field" to "payee", "value" to payee),
                    mapOf("op" to "is", "field" to "account", "value" to account),
                    mapOf("op" to "isapprox", "field" to "date", "value" to scheduleDate(next)),
                    mapOf("op" to "isapprox", "field" to "amount", "value" to amount),
                ),
                listOf(
                    mapOf("op" to "set", "field" to "category", "value" to category),
                    mapOf("op" to "link-schedule", "value" to scheduleId),
                ),
            )
            db.execute(
                "INSERT INTO schedules (id, rule, name, posts_transaction, custom_upcoming_length, completed, tombstone) VALUES (?, ?, ?, 1, NULL, 0, 0)",
                scheduleId, ruleId, name,
            )
            db.execute(
                """
                INSERT INTO schedules_next_date (id, schedule_id, local_next_date, local_next_date_ts, base_next_date, base_next_date_ts)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                uuid(), scheduleId, next, now, next, now,
            )
        }

        // --- Budgets (current month) ---
        val budgets = mutableListOf(
            groceriesId to 60000L, rentId to 185_000L, utilitiesId to 10000L, internetId to 7000L, fuelId to 15000L,
            transitId to 8000L, parkingId to 2000L, diningId to 20000L, coffeeId to 4000L, entertainmentId to 5000L,
            shoppingId to 15000L, gymId to 3500L, pharmacyId to 3000L, carPaymentId to 42500L,
        )
        // Tracking budgets also budget income (unlike envelope), so the Saved /
        // Projected savings summary has a budgeted-income figure to work with.
        // Matches the two 320,000 monthly paychecks.
        if (tracking) budgets += salaryId to 640_000L
        val budgetsTable = if (tracking) "reflect_budgets" else "zero_budgets"
        for ((categoryId, amount) in budgets) {
            db.execute("INSERT INTO $budgetsTable (id, month, category, amount, carryover) VALUES (?, ?, ?, ?, 0)", uuid(), yyyymm, categoryId, amount)
        }

        // --- Preferences ---
        db.execute("INSERT INTO preferences (id, value) VALUES ('defaultCurrencyCode', 'USD')")
        // Mirror a real tracking file: budgetType drives which budget table is
        // live when both exist (harmless here where only one does).
        if (tracking) db.execute("INSERT INTO preferences (id, value) VALUES ('budgetType', 'tracking')")
        // The loan and deposit configs, stored the way SyncClient writes them.
        db.execute(
            "INSERT INTO preferences (id, value) VALUES (?, ?), (?, ?)",
            PreferenceKeys.loan(carLoanId), AppJson.encodeToString(LoanConfig.serializer(), loanConfig),
            PreferenceKeys.deposit(cdId), AppJson.encodeToString(DepositConfig.serializer(), depositConfig),
        )
        // Card mappings, one row per keyword as SyncClient writes them. Apple
        // Card gets two so the grouped, multi-keyword row shows in the demo.
        for ((keyword, accountId) in listOf("4417" to appleCardId, "Goldman Sachs" to appleCardId, "8830" to chaseId)) {
            db.execute("INSERT INTO preferences (id, value) VALUES (?, ?)", PreferenceKeys.cardMapping(keyword), accountId)
        }

        // --- Reports dashboards ---
        // Two pages so the demo exercises the dashboard switcher (GH #120);
        // "Main" is inserted first so it's the default dashboard on open.
        val mainPageId = uuid()
        val trendsPageId = uuid()
        db.execute("INSERT INTO dashboard_pages (id, name, tombstone) VALUES (?, 'Main', 0), (?, 'Trends', 0)", mainPageId, trendsPageId)

        // A curated set of widgets so the Reports tab is populated in the demo.
        // Sliding-window time frames slide their stored range forward to end at
        // the current month, so the trends always cover the seeded data
        // regardless of when the demo is loaded. Ordered by `y`.
        insertWidget(
            db, mainPageId, "markdown-card", 0,
            """{"content":"**Welcome to the demo** 👋\n\nThis is sample data stored only on this device — nothing you do here can touch a server or a real budget. When you’re ready, connect your own Actual Budget server in **More → Connection & Data**."}""",
        )
        insertWidget(db, mainPageId, "net-worth-card", 1, """{"name":"Net Worth","timeFrame":{"start":"2024-01","end":"2024-06","mode":"sliding-window"},"interval":"Monthly"}""")
        insertWidget(db, mainPageId, "cash-flow-card", 2, """{"name":"Cash Flow","timeFrame":{"start":"2024-01","end":"2024-06","mode":"sliding-window"},"showBalance":false}""")
        insertWidget(
            db, mainPageId, "summary-card", 3,
            """{"name":"Spent This Month","content":"{\"type\":\"sum\"}","conditions":[{"field":"amount","op":"lt","value":0}],"conditionsOp":"and"}""",
        )
        insertWidget(db, mainPageId, "spending-card", 4, """{"name":"This Month","mode":"single-month"}""")
        insertWidget(db, mainPageId, "spending-card", 5, """{"name":"Budget Overview","mode":"budget"}""")
        insertWidget(db, mainPageId, "spending-card", 6, """{"name":"3-Month Average","mode":"average"}""")

        // The second dashboard: a small page that shows off switching.
        insertWidget(
            db, trendsPageId, "markdown-card", 0,
            """{"content":"**A second dashboard** 📊\n\nBudgets can have several report dashboards — switch between them with the menu in the top corner. Create and arrange dashboards in the Actual Budget webapp."}""",
        )
        insertWidget(db, trendsPageId, "age-of-money-card", 1, """{"name":"Age of Money"}""")
        insertWidget(
            db, trendsPageId, "summary-card", 2,
            """{"name":"Income This Month","content":"{\"type\":\"sum\"}","conditions":[{"field":"amount","op":"gt","value":0}],"conditionsOp":"and"}""",
        )

        logger.info("Inserted ${transactions.size} demo transactions for month $yyyymm")
    }

    private data class ScheduleSeed(val name: String, val payee: String, val account: String, val category: String, val amount: Long, val day: Int)

    private fun insertRule(db: Db, id: String, conditions: List<Map<String, Any>>, actions: List<Map<String, Any>>) {
        db.execute(
            "INSERT INTO rules (id, stage, conditions_op, conditions, actions, tombstone) VALUES (?, NULL, 'and', ?, ?, 0)",
            id, json(conditions).toString(), json(actions).toString(),
        )
    }

    private fun insertWidget(db: Db, pageId: String, type: String, y: Int, meta: String) {
        db.execute(
            "INSERT INTO dashboard (id, type, dashboard_page_id, x, y, width, height, meta, tombstone) VALUES (?, ?, ?, 0, ?, 12, 2, ?, 0)",
            uuid(), type, pageId, y, meta,
        )
    }

    // Insert helpers

    private fun insertAccount(db: Db, id: String, name: String, type: String, sortOrder: Double, offBudget: Boolean = false) {
        db.execute(
            "INSERT INTO accounts (id, name, type, offbudget, closed, tombstone, sort_order) VALUES (?, ?, ?, ?, 0, 0, ?)",
            id, name, type, if (offBudget) 1 else 0, sortOrder,
        )
    }

    private fun insertCategoryGroup(db: Db, id: String, name: String, isIncome: Boolean, sortOrder: Double) {
        db.execute(
            "INSERT INTO category_groups (id, name, is_income, sort_order, tombstone, hidden) VALUES (?, ?, ?, ?, 0, 0)",
            id, name, if (isIncome) 1 else 0, sortOrder,
        )
    }

    private fun insertCategory(db: Db, id: String, name: String, groupId: String, isIncome: Boolean, sortOrder: Double) {
        db.execute(
            "INSERT INTO categories (id, name, is_income, cat_group, sort_order, tombstone, hidden) VALUES (?, ?, ?, ?, ?, 0, 0)",
            id, name, if (isIncome) 1 else 0, groupId, sortOrder,
        )
    }

    private fun insertNote(db: Db, id: String, note: String) = db.execute("INSERT INTO notes (id, note) VALUES (?, ?)", id, note)

    private fun insertPayee(db: Db, id: String, name: String?, transferAccountId: String? = null) {
        db.execute("INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES (?, ?, ?, 0)", id, name, transferAccountId)
        // Required for the transactions JOIN to resolve payee name
        db.execute("INSERT INTO payee_mapping (id, targetId) VALUES (?, ?)", id, id)
    }

    private fun insertTag(db: Db, id: String, tag: String, color: String?, description: String?) {
        db.execute("INSERT INTO tags (id, tag, color, description, tombstone) VALUES (?, ?, ?, ?, 0)", id, tag, color, description)
    }

    private fun insertTransaction(db: Db, t: SeedTransaction, sortOrder: Double) {
        db.execute(
            """
            INSERT INTO transactions (
                id, isParent, isChild, acct, category, amount, description, notes, date,
                starting_balance_flag, sort_order, tombstone, cleared, reconciled, transferred_id
            )
            VALUES (?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?)
            """,
            t.id, t.account, t.category, t.amount, t.payee, t.notes, t.date,
            if (t.startingBalance) 1 else 0, sortOrder, if (t.cleared) 1 else 0, t.transfer,
        )
    }
}

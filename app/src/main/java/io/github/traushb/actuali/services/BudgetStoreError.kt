package io.github.traushb.actuali.services

import io.github.traushb.actuali.services.rules.RuleSchema
import java.util.Locale

/** Refusals the store raises before a write, worded for the person who made the request. */
sealed class BudgetStoreError : Exception() {
    object SyncNotConfigured : BudgetStoreError()
    object TransferAccountsMatch : BudgetStoreError()
    object TransferAmountNotPositive : BudgetStoreError()
    object TransferPayeeMissing : BudgetStoreError()
    object TransferCategoriesMatch : BudgetStoreError()
    object TransferAmountExceedsSource : BudgetStoreError()
    object InvalidAmount : BudgetStoreError()
    object MissingTransferDestination : BudgetStoreError()
    data class PayeeCreationFailed(val detail: String) : BudgetStoreError()
    object TransferPartnerMissing : BudgetStoreError()
    object CannotConvertToTransfer : BudgetStoreError()
    object CannotConvertToSplit : BudgetStoreError()
    object SplitNeedsTwoLines : BudgetStoreError()
    object SplitAmountMismatch : BudgetStoreError()
    object InvalidAccountName : BudgetStoreError()
    data class AccountCreationFailed(val detail: String) : BudgetStoreError()
    object InvalidCategoryName : BudgetStoreError()
    object InvalidCategoryGroupName : BudgetStoreError()
    data class CategoryCreationFailed(val detail: String) : BudgetStoreError()
    data class CategoryUpdateFailed(val detail: String) : BudgetStoreError()
    data class CategoryGroupCreationFailed(val detail: String) : BudgetStoreError()
    data class CategoryGroupUpdateFailed(val detail: String) : BudgetStoreError()
    object RuleNeedsCondition : BudgetStoreError()
    object RuleNeedsAction : BudgetStoreError()
    data class RuleInvalidCondition(val field: String, val op: String) : BudgetStoreError()
    object RuleInvalidAction : BudgetStoreError()
    data class RuleEmptyValue(val field: String) : BudgetStoreError()
    data class RuleInvalidPattern(val pattern: String) : BudgetStoreError()
    object RuleOwnedBySchedule : BudgetStoreError()
    object RuleNotSerializable : BudgetStoreError()
    object BankSyncNotConfigured : BudgetStoreError()
    object InvalidTagName : BudgetStoreError()
    object TagAlreadyExists : BudgetStoreError()
    data class TagCreationFailed(val detail: String) : BudgetStoreError()
    data class TagUpdateFailed(val detail: String) : BudgetStoreError()
    object LoanAccountOnBudget : BudgetStoreError()

    override val message: String get() = message(Locale.getDefault())

    fun message(locale: Locale): String = when (this) {
        SyncNotConfigured -> ReportStrings.text("error.syncNotConfigured", locale)
        TransferAccountsMatch -> ReportStrings.text("error.transferAccountsMatch", locale)
        TransferAmountNotPositive -> ReportStrings.text("error.transferAmountNotPositive", locale)
        TransferPayeeMissing -> ReportStrings.text("error.transferPayeeMissing", locale)
        TransferCategoriesMatch -> ReportStrings.text("error.transferCategoriesMatch", locale)
        TransferAmountExceedsSource -> ReportStrings.text("error.transferAmountExceedsSource", locale)
        InvalidAmount -> ReportStrings.text("error.invalidAmount", locale)
        MissingTransferDestination -> ReportStrings.text("error.missingTransferDestination", locale)
        is PayeeCreationFailed -> ReportStrings.format("error.payeeCreationFailed %@", detail, locale = locale)
        TransferPartnerMissing -> ReportStrings.text("error.transferPartnerMissing", locale)
        CannotConvertToTransfer -> ReportStrings.text("error.cannotConvertToTransfer", locale)
        CannotConvertToSplit -> ReportStrings.text("error.cannotConvertToSplit", locale)
        SplitNeedsTwoLines -> ReportStrings.text("error.splitNeedsTwoLines", locale)
        SplitAmountMismatch -> ReportStrings.text("error.splitAmountMismatch", locale)
        InvalidAccountName -> ReportStrings.text("error.invalidAccountName", locale)
        is AccountCreationFailed -> ReportStrings.format("error.accountCreationFailed %@", detail, locale = locale)
        InvalidCategoryName -> ReportStrings.text("error.invalidCategoryName", locale)
        InvalidCategoryGroupName -> ReportStrings.text("error.invalidCategoryGroupName", locale)
        is CategoryCreationFailed -> ReportStrings.format("error.categoryCreationFailed %@", detail, locale = locale)
        is CategoryUpdateFailed -> ReportStrings.format("error.categoryUpdateFailed %@", detail, locale = locale)
        is CategoryGroupCreationFailed -> ReportStrings.format("error.categoryGroupCreationFailed %@", detail, locale = locale)
        is CategoryGroupUpdateFailed -> ReportStrings.format("error.categoryGroupUpdateFailed %@", detail, locale = locale)
        RuleNeedsCondition -> ReportStrings.text("error.ruleNeedsCondition", locale)
        RuleNeedsAction -> ReportStrings.text("error.ruleNeedsAction", locale)
        is RuleInvalidCondition -> ReportStrings.format(
            "error.ruleInvalidCondition %@ %@",
            RuleSchema.opLabel(op, RuleSchema.fieldType(field), locale),
            RuleSchema.label(field, locale = locale),
            locale = locale,
        )
        RuleInvalidAction -> ReportStrings.text("error.ruleInvalidAction", locale)
        is RuleEmptyValue -> ReportStrings.format(
            "error.ruleEmptyValue %@",
            RuleSchema.sentenceCased(RuleSchema.label(field, locale = locale), locale),
            locale = locale,
        )
        is RuleInvalidPattern -> ReportStrings.format("error.ruleInvalidPattern %@", pattern, locale = locale)
        RuleOwnedBySchedule -> ReportStrings.text("error.ruleOwnedBySchedule", locale)
        RuleNotSerializable -> ReportStrings.text("error.ruleNotSerializable", locale)
        BankSyncNotConfigured -> ReportStrings.text("error.bankSyncNotConfigured", locale)
        InvalidTagName -> ReportStrings.text("error.invalidTagName", locale)
        TagAlreadyExists -> ReportStrings.text("error.tagAlreadyExists", locale)
        is TagCreationFailed -> ReportStrings.format("error.tagCreationFailed %@", detail, locale = locale)
        is TagUpdateFailed -> ReportStrings.format("error.tagUpdateFailed %@", detail, locale = locale)
        LoanAccountOnBudget -> ReportStrings.text("error.loanAccountOnBudget", locale)
    }
}

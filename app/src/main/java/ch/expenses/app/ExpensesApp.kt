package ch.expenses.app

import android.app.Application
import ch.expenses.app.data.AppDatabase
import ch.expenses.app.data.ReceiptRepository

class ExpensesApp : Application() {
    val repository: ReceiptRepository by lazy {
        ReceiptRepository(this, AppDatabase.create(this).receipts())
    }
}

package org.fossify.contacts.fragments

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Toast
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.activities.EditContactActivity
import org.fossify.contacts.activities.InsertOrEditContactActivity
import org.fossify.contacts.activities.MainActivity
import org.fossify.contacts.activities.SimpleActivity
import org.fossify.contacts.adapters.ContactsAdapter
import org.fossify.contacts.databinding.FragmentContactsBinding
import org.fossify.contacts.databinding.FragmentLettersLayoutBinding
import org.fossify.contacts.extensions.config
import org.fossify.contacts.extensions.viewContact
import org.fossify.contacts.helpers.AVOID_CHANGING_TEXT_TAG
import org.fossify.contacts.helpers.LOCATION_CONTACTS_TAB
import org.fossify.contacts.interfaces.RefreshContactsListener
import org.fossify.contacts.rosenweg.RosenwegNextcloud

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LetterLayout>(context, attributeSet) {

    private lateinit var binding: FragmentContactsBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentContactsBinding.bind(this)
        innerBinding = LetterLayout(FragmentLettersLayoutBinding.bind(binding.root))
    }

    override fun fabClicked() {
        activity?.hideKeyboard()
        // Rosenweg: privates Handy ohne Nextcloud - keine neuen lokalen Kontakte, erst verbinden
        if (RosenwegNextcloud.ohneZiel(context)) {
            context.toast(RosenwegNextcloud.HINWEIS_NEU, Toast.LENGTH_LONG)
            activity?.let { RosenwegNextcloud.verbinden(it) }
            return
        }
        Intent(context, EditContactActivity::class.java).apply {
            context.startActivity(this)
        }
    }

    override fun placeholderClicked() {
        // Rosenweg: privates Handy - kein Quellen-Filter, stattdessen verbinden bzw. neuer Kontakt
        if (RosenwegNextcloud.privat(context)) {
            if (RosenwegNextcloud.aktiv(context)) fabClicked() else activity?.let { RosenwegNextcloud.verbinden(it) }
            return
        }
        if (activity is MainActivity) {
            (activity as MainActivity).showFilterDialog()
        } else if (activity is InsertOrEditContactActivity) {
            (activity as InsertOrEditContactActivity).showFilterDialog()
        }
    }

    fun setupContactsAdapter(contacts: List<Contact>) {
        setupViewVisibility(contacts.isNotEmpty())
        if (contacts.isEmpty() && innerBinding.fragmentPlaceholder.tag != AVOID_CHANGING_TEXT_TAG) {
            RosenwegNextcloud.leerText(context)?.let { (text, knopf) ->
                innerBinding.fragmentPlaceholder.text = text
                innerBinding.fragmentPlaceholder2.text = knopf
                innerBinding.fragmentPlaceholder2.underlineText()
            }
        }
        val currAdapter = innerBinding.fragmentList.adapter

        if (currAdapter == null || forceListRedraw) {
            forceListRedraw = false
            val location = LOCATION_CONTACTS_TAB

            ContactsAdapter(
                activity = activity as SimpleActivity,
                contactItems = contacts.toMutableList(),
                refreshListener = activity as RefreshContactsListener,
                location = location,
                removeListener = null,
                recyclerView = innerBinding.fragmentList,
                enableDrag = false,
                itemClick = {
                    (activity as RefreshContactsListener).contactClicked(it as Contact)
                },
                profileIconClick = {
                    activity?.viewContact(it as Contact)
                }
            ).apply {
                innerBinding.fragmentList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                innerBinding.fragmentList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).apply {
                startNameWithSurname = context.config.startNameWithSurname
                showPhoneNumbers = context.config.showPhoneNumbers
                showContactThumbnails = context.config.showContactThumbnails
                updateItems(contacts)
            }
        }
    }
}

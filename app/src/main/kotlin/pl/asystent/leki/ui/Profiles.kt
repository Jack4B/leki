package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.ProfileInfo
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.Cykl
import pl.asystent.leki.core.CyklData
import pl.asystent.leki.core.plN

// pary (tło, litera) dla kolejnych osób: kontrast litery co najmniej 5:1 w obu motywach
private val tintsLight = listOf(0xFFDDEEE8 to 0xFF0B6B58, 0xFFDCE8F7 to 0xFF1F4F8F, 0xFFF3E3F4 to 0xFF7A2C84, 0xFFFBE6D6 to 0xFF8A3F00, 0xFFE7E6F9 to 0xFF4A3FA8, 0xFFF8DCE3 to 0xFF9C1F46)
private val tintsDark = listOf(0xFF17332B to 0xFF4DC2A5, 0xFF17283F to 0xFF8DB8F2, 0xFF34203A to 0xFFD9A3E3, 0xFF3A2615 to 0xFFF0B27A, 0xFF25224A to 0xFFB7B0F5, 0xFF3D1D28 to 0xFFF2A0B8)

/** Kółko z pierwszą literą imienia w kolorze przypisanym osobie: pokazuje, czyje dane są teraz na ekranie. */
@Composable
fun PersonAvatar(name: String, modifier: Modifier = Modifier, size: Int = 24) {
    val tints = if (pal.dark) tintsDark else tintsLight
    val (bg, fg) = tints[Math.floorMod(name.trim().hashCode(), tints.size)]
    Box(modifier.size(size.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(bg)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, style = TextStyle(fontSize = (size * 0.5).sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color(fg)))
    }
}

/**
 * Wybór osoby w nagłówku: pod nazwą aplikacji widać, czyje dane są na ekranie. Dotknięcie otwiera listę osób z możliwością
 * dodania nowej i przejścia do zarządzania osobami (Ustawienia).
 */
@Composable
fun PersonSwitcher(store: Store, profiles: List<ProfileInfo>, activeId: String, onManage: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val name = profiles.firstOrNull { it.id == activeId }?.name ?: profiles.firstOrNull()?.name ?: "Osoba 1"
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(CtlShape).clickable(role = Role.Button, onClickLabel = "Zmień osobę") { open = true }
                .semantics { contentDescription = "Osoba: $name" }.padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonAvatar(name)
            Spacer(Modifier.width(8.dp))
            Text(name, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Ph.CaretRight, contentDescription = null, tint = pal.ink3, modifier = Modifier.size(14.dp).rotate(90f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = pal.surface) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, style = TextStyle(fontSize = 15.sp, color = pal.ink, fontWeight = if (p.id == activeId) FontWeight.SemiBold else FontWeight.Normal)) },
                    leadingIcon = { if (p.id == activeId) Icon(Ph.Check, contentDescription = "Wybrana", tint = pal.accent, modifier = Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp)) },
                    onClick = { open = false; store.switchTo(p.id) },
                )
            }
            DropdownMenuItem(
                text = { Text("Dodaj osobę", style = TextStyle(fontSize = 15.sp, color = pal.accent)) },
                leadingIcon = { Icon(Ph.Plus, contentDescription = null, tint = pal.accent, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; adding = true },
            )
            DropdownMenuItem(
                text = { Text("Zarządzaj osobami", style = TextStyle(fontSize = 15.sp, color = pal.ink2)) },
                leadingIcon = { Icon(Ph.User, contentDescription = null, tint = pal.ink3, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onManage() },
            )
        }
    }
    if (adding) AddPersonDialog(store, onDismiss = { adding = false })
}

/** Okno dodawania osoby: imię, potem od razu przechodzimy na jej dane. */
@Composable
fun AddPersonDialog(store: Store, onDismiss: () -> Unit) {
    val toast = LocalToast.current
    var name by remember { mutableStateOf("") }
    val taken = name.isNotBlank() && store.profiles.value.any { it.name.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowa osoba", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BodyText("Każda osoba ma osobne leki, ciśnienie, badania, raporty i dziennik cyklu. Wspólne są tylko wygląd i blokada aplikacji.")
                LekiField("Imię", name, { name = it }, placeholder = "np. Anna")
                if (taken) ErrorText("Osoba o takim imieniu już jest. Dodaj coś, co je odróżni (np. inicjał nazwiska).")
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !taken, onClick = {
                val label = name.trim()
                val id = store.addProfile(label)
                onDismiss()
                if (id == null) toast("Osiągnięto limit osób (${Backup.MAX_PROFILES}).") else { store.switchTo(id); toast("Dodano osobę: $label. Teraz widać dane tej osoby.") }
            }) { Text("Dodaj", color = if (name.isNotBlank() && !taken) pal.accent else pal.ink3, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

/** Ustawienia → Osoby: lista osób, przełączanie, usuwanie, dodawanie i przeniesienie dziennika cyklu do innej osoby. */
@Composable
fun PeopleSection(s: AppState, store: Store) {
    val toast = LocalToast.current
    val undo = LocalUndo.current
    var adding by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    var moveTo by remember { mutableStateOf<ProfileInfo?>(null) }
    // s jest tu po to, żeby lista odświeżała się po każdej zmianie danych aktywnej osoby
    val all = remember(s, store.profiles.value) { store.allStates() }
    val activeId = store.activeId.value
    val active = all.firstOrNull { it.first.id == activeId }

    LekiCard {
        Column {
            all.forEachIndexed { i, (info, st) ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonAvatar(info.name, size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(info.name + if (info.id == activeId) " (wybrana osoba)" else "", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        // tylko to, co ma dane (zera nic nie mówią)
                        val parts = listOfNotNull(
                            st.meds.size.takeIf { it > 0 }?.let { "$it ${plN(it, "lek", "leki", "leków")}" },
                            st.bp.size.takeIf { it > 0 }?.let { "$it ${plN(it, "pomiar", "pomiary", "pomiarów")} ciśnienia" },
                            st.labs.size.takeIf { it > 0 }?.let { "$it ${plN(it, "wynik", "wyniki", "wyników")} badań" },
                            if (st.settings.cyklOn || !st.cykl.isEmpty) "dziennik cyklu" else null,
                        )
                        Text(
                            parts.joinToString(", ").ifEmpty { "Brak danych" },
                            style = TextStyle(fontSize = 12.5.sp, color = pal.ink3),
                        )
                    }
                    if (info.id != activeId) LekiButton("Przełącz", { store.switchTo(info.id) }, Modifier.semantics { contentDescription = "Przełącz na: ${info.name}" }, small = true, tonal = true)
                }
            }
        }
    }
    Hint("Imię zmienisz w Ustawieniach → Ogólne, gdy dana osoba jest wybrana.", Modifier.padding(top = 8.dp))
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LekiButton("Dodaj osobę", { adding = true }, Modifier.fillMaxWidth(), primary = true, icon = Ph.Plus)
        if (all.size > 1 && active != null) LinkButton("Usuń osobę: ${active.first.name}", { removeId = active.first.id }, danger = true)
    }

    // dziennik cyklu zwykle prowadzi się dla jednej osoby; tu można go przenieść do innej (razem z wpisanymi w nim chorobami i lekami)
    if (active != null && !active.second.cykl.isEmpty) {
        val targets = all.filter { it.first.id != activeId && it.second.cykl.isEmpty }
        if (targets.isNotEmpty()) {
            Spacer(Modifier.size(18.dp))
            Overline("Dziennik cyklu")
            LekiCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BodyText("Dziennik cyklu jest teraz przypisany do: ${active.first.name}. Możesz go przenieść do innej osoby razem z wpisanymi w nim chorobami i lekami. Raporty i analiza AI korzystają z danych wybranej osoby.")
                    targets.forEach { (info, _) -> LekiButton("Przenieś do: ${info.name}", { moveTo = info }, Modifier.fillMaxWidth(), tonal = true) }
                }
            }
        }
    }

    if (adding) AddPersonDialog(store, onDismiss = { adding = false })
    removeId?.let { id ->
        val who = all.firstOrNull { it.first.id == id }?.first?.name ?: "tę osobę"
        ConfirmDialog(
            "Usunąć osobę: $who?", "Znikną wszystkie dane tej osoby: leki, odhaczenia, pomiary ciśnienia, wyniki badań, EKG, analizy i dziennik cyklu. Tej operacji nie da się cofnąć, chyba że masz kopię zapasową.",
            "Usuń osobę", onConfirm = { removeId = null; if (store.removeProfile(id)) toast("Usunięto osobę: $who") }, onDismiss = { removeId = null }, danger = true,
        )
    }
    moveTo?.let { dest ->
        val from = active?.first
        ConfirmDialog(
            "Przenieść dziennik cyklu?", "Dziennik cyklu (wpisy, choroby i leki z modułu) przejdzie z osoby: ${from?.name} do osoby: ${dest.name}. Dla osoby ${from?.name} moduł zostanie ukryty i pusty. Możesz to cofnąć zaraz po przeniesieniu.",
            "Przenieś", onConfirm = {
                moveTo = null
                val src = store.profileState(activeId) ?: return@ConfirmDialog
                if (src.cykl.isEmpty) return@ConfirmDialog
                val destBefore = store.profileState(dest.id) ?: return@ConfirmDialog
                val srcWasOn = src.settings.cyklOn
                store.updateProfile(dest.id) { it.copy(cykl = src.cykl, settings = it.settings.copy(cyklOn = true)) }
                store.updateProfile(activeId) { it.copy(cykl = CyklData(), settings = it.settings.copy(cyklOn = false)) }
                undo("Przeniesiono dziennik cyklu do: ${dest.name}") {
                    store.updateProfile(activeId) { it.copy(cykl = src.cykl, settings = it.settings.copy(cyklOn = srcWasOn)) }
                    store.updateProfile(dest.id) { it.copy(cykl = destBefore.cykl, settings = it.settings.copy(cyklOn = destBefore.settings.cyklOn)) }
                }
            }, onDismiss = { moveTo = null },
        )
    }
}

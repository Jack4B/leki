package pl.asystent.leki.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.parseNum
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Komunikat chwilowy (snackbar); dostarczany przez MainActivity. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

val CardShape = RoundedCornerShape(18.dp)
val CtlShape = RoundedCornerShape(12.dp)

@Composable
fun LekiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = CardShape, color = pal.surface, border = BorderStroke(1.dp, pal.line)) {
        Column(content = content)
    }
}

/** Wiersz listy z kreską oddzielającą od poprzedniego. */
@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.weight(1f, fill = false))
        if (action != null) action()
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = pal.ink3))
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color = pal.ink2) {
    Text(text, modifier = modifier, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = color))
}

@Composable
fun PhIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = pal.ink3) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

@Composable
fun LekiButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null, small: Boolean = false, tonal: Boolean = false,
) {
    val bg = if (primary) pal.accent else if (tonal) pal.accentSoft else pal.surface
    val fg = if (primary) pal.accentInk else if (danger) pal.danger else if (tonal) pal.accent else pal.ink
    val border = if (primary) pal.accent else if (tonal) pal.accentSoft else pal.line
    Row(
        modifier = modifier
            .heightIn(min = if (small) 40.dp else 48.dp)
            .clip(CtlShape)
            .background(bg.copy(alpha = if (enabled) 1f else 0.45f))
            .border(1.dp, border.copy(alpha = if (enabled) 1f else 0.45f), CtlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = fg); Spacer(Modifier.size(8.dp)) }
        Text(text, style = TextStyle(fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = fg), textAlign = TextAlign.Center)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    Text(
        text, modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (danger) pal.danger else pal.accent),
    )
}

/** Pole tekstowe z etykietą nad nim, w stylu aplikacji. */
@Composable
fun LekiField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, mono: Boolean = false,
    minLines: Int = 1, readOnly: Boolean = false, password: Boolean = false, error: Boolean = false,
) {
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines, readOnly = readOnly,
            isError = error,
            textStyle = TextStyle(fontSize = 16.sp, color = pal.ink, fontFamily = if (mono) Mono else null),
            placeholder = if (placeholder.isNotEmpty()) { { Text(placeholder, style = TextStyle(fontSize = 15.sp, color = pal.ink3)) } } else null,
            shape = CtlShape,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = pal.accent, unfocusedBorderColor = pal.line, focusedContainerColor = pal.surface, unfocusedContainerColor = pal.surface,
                cursorColor = pal.accent, errorBorderColor = pal.danger,
            ),
        )
    }
}

/** Pole na liczbę całkowitą w zakresie: wartość jest zatwierdzana na bieżąco, jeśli mieści się w zakresie, a po opuszczeniu pola poprawiana. */
@Composable
fun IntField(label: String, value: Int, onValue: (Int) -> Unit, range: IntRange, modifier: Modifier = Modifier, placeholder: String = "") {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var hadFocus by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                val f = t.filter { it.isDigit() }.take(4)
                text = f
                f.toIntOrNull()?.let { if (it in range) onValue(it) }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
                // poprawiamy wartość dopiero po utracie fokusu, a nie przy samym wyświetleniu pola (to wywoływało zbędny zapis przy otwarciu ekranu)
                if (st.isFocused) hadFocus = true
                else if (hadFocus) {
                    hadFocus = false
                    val n = (text.toIntOrNull() ?: value).coerceIn(range.first, range.last)
                    text = n.toString(); onValue(n)
                }
            },
            singleLine = true, shape = CtlShape,
            textStyle = TextStyle(fontSize = 16.sp, color = pal.ink, fontFamily = Mono),
            placeholder = if (placeholder.isNotEmpty()) { { Text(placeholder, style = TextStyle(fontSize = 15.sp, color = pal.ink3)) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = pal.accent, unfocusedBorderColor = pal.line, focusedContainerColor = pal.surface, unfocusedContainerColor = pal.surface, cursorColor = pal.accent),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Wybierz datę") {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
        PickerBox(if (date != null) plDate(date) else placeholder, date == null) { open = true }
    }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Anuluj") } },
        ) { DatePicker(state = st) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, time: LocalTime?, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
        PickerBox(time?.hhmm() ?: "Wybierz godzinę", time == null) { open = true }
    }
    if (open) TimeDialog(time ?: LocalTime.now().withSecond(0).withNano(0), onDismiss = { open = false }, onPick = { onChange(it); open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val st = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(st.hour, st.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        text = { TimePicker(state = st) },
        containerColor = pal.surface,
    )
}

@Composable
private fun PickerBox(text: String, placeholder: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CtlShape).border(1.dp, pal.line, CtlShape).background(pal.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = TextStyle(fontSize = 16.sp, fontFamily = Mono, color = if (placeholder) pal.ink3 else pal.ink))
    }
}

/** Rząd przycisków do wyboru jednej opcji. */
@Composable
fun SegRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(CtlShape).border(1.dp, pal.line, CtlShape).background(pal.surface)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) pal.accent else pal.surface)
                    .clickable(role = Role.RadioButton) { onSelect(i) }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = TextStyle(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) pal.accentInk else pal.ink2), textAlign = TextAlign.Center)
            }
            if (i < options.lastIndex) Box(Modifier.width1().height(44.dp).background(pal.line))
        }
    }
}

private fun Modifier.width1() = this.then(Modifier.size(width = 1.dp, height = 44.dp))

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(CtlShape).border(1.dp, if (selected) pal.accent else pal.line, CtlShape)
            .background(if (selected) pal.accent else pal.surface).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) pal.accentInk else pal.ink2)) }
}

/** Wiersz z polem wyboru (lista opcji). */
@Composable
fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, indent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { onChange(!checked) }.padding(start = if (indent) 42.dp else 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).border(2.dp, if (checked) pal.accent else pal.ink3, RoundedCornerShape(5.dp)).background(if (checked) pal.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Ph.Check, null, Modifier.size(14.dp), tint = pal.accentInk) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = pal.ink))
            if (subtitle != null) Text(subtitle, style = TextStyle(fontSize = 12.sp, color = pal.ink3))
        }
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = pal.ink, mono: Boolean = true) {
    Column(modifier) {
        Text(value, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = if (mono) Mono else null, color = valueColor))
        Text(label, style = TextStyle(fontSize = 12.sp, color = pal.ink3))
    }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier, action: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).border(1.dp, pal.line, CardShape).padding(horizontal = 16.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = TextStyle(fontSize = 14.sp, color = pal.ink3), textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

@Composable
fun AlertBox(title: String, modifier: Modifier = Modifier, body: String? = null, content: (@Composable ColumnScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(CardShape).background(pal.warnBg).border(1.dp, pal.warnLine, CardShape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhIcon(Ph.CalendarCheck, size = 22.dp, tint = pal.warn)
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
            if (!body.isNullOrEmpty()) Text(body, style = TextStyle(fontSize = 13.5.sp, color = pal.ink2), modifier = Modifier.padding(top = 2.dp))
            if (content != null) content()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = { Text(text, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = pal.ink2)) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmText, color = if (danger) pal.danger else pal.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

/** Edytor na całym ekranie z paskiem tytułu. */
@Composable
fun FullScreenDialog(title: String, onClose: () -> Unit, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = pal.bg) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).background(pal.bg).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Zamknij" }, contentAlignment = Alignment.Center) {
                        PhIcon(Ph.X, size = 22.dp, tint = pal.ink)
                    }
                    Text(title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(start = 8.dp))
                }
                Divider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                if (footer != null) {
                    Divider()
                    Box(Modifier.fillMaxWidth().background(pal.surface).padding(horizontal = 16.dp, vertical = 12.dp)) { footer() }
                }
            }
        }
    }
}

/** Pasek postępu (np. udział przyjętych dawek). */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(pal.sunken)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(pal.accent))
    }
}

@Composable
fun ErrorText(text: String) {
    if (text.isNotEmpty()) Text(text, style = TextStyle(fontSize = 13.sp, color = pal.danger))
}

fun parseDecimal(s: String): Double? = parseNum(s)

/** Lista rozwijana z jednym wyborem (np. parametr badania). */
@Composable
fun DropdownField(label: String, options: List<Pair<String, String>>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CtlShape).border(1.dp, pal.line, CtlShape).background(pal.surface)
                    .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(options.firstOrNull { it.first == selectedId }?.second ?: "Wybierz", style = TextStyle(fontSize = 16.sp, color = pal.ink), modifier = Modifier.weight(1f))
                PhIcon(Ph.CaretRight, size = 16.dp)
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, text) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(text, color = pal.ink) }, onClick = { onSelect(id); open = false })
                }
            }
        }
    }
}

/** Przełączana pigułka z ptaszkiem (wybór wielu opcji). */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, if (selected) pal.accent else pal.line, RoundedCornerShape(20.dp))
            .background(if (selected) pal.accentSoft else pal.surface).clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) { Icon(Ph.Check, null, Modifier.size(15.dp), tint = pal.accent); Spacer(Modifier.size(6.dp)) }
        Text(text, style = TextStyle(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) pal.accent else pal.ink2))
    }
}

/** Mały nagłówek grupy nad listą. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(bottom = 8.dp), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink3, letterSpacing = 0.8.sp))
}

/** Przycisk z samą ikoną (np. kalendarz, wstecz). */
@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = true) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(if (tonal) pal.accentSoft else Color.Transparent).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = if (tonal) pal.accent else pal.ink) }
}

/** Ikona w kolorowym kółku, np. przy wierszu ustawień. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.accentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = pal.accent) }
}

/** Okno wyboru daty uruchamiane samą ikoną kalendarza. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerButton(date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconAction(Ph.CalendarBlank, "Wybierz datę", { open = true }, modifier)
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Anuluj") } },
        ) { DatePicker(state = st) }
    }
}

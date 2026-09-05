/*
 * Chat capture screen (F-1.11).
 *
 * The rule the whole file is built around: a message the backend read completely
 * and confidently is already recorded by the time the reply arrives, and each
 * recorded entry gets a card with an Undo. Nothing is approved in advance.
 *
 * One reply can carry several entries -- "$28 on coffee, $350 on groceries" is two
 * -- so the transcript is rendered from reply.results, one turn each.
 *
 * The preview is the exception, not the path: it appears only for a draft the model
 * was too unsure to write, and ends at its own Create button (/chat/confirm).
 */
(function () {
    'use strict';

    var API = '/api/v1';
    var TOKEN_KEY = 'zenz.accessToken';
    var PLACEHOLDER = 'e.g. I spent $20 — or ask how to spend less';

    var state = {
        token: sessionStorage.getItem(TOKEN_KEY),
        sessionId: null,
        messageId: null,
        draft: null,
        status: null,
        categories: []
    };

    var el = {};
    ['signin', 'email', 'password', 'signin-error', 'conversation', 'transcript',
     'insight', 'insight-months',
     'preview', 'p-type', 'p-amount', 'p-category', 'p-note', 'p-date', 'p-cadence',
     'p-cadence-label', 'edit', 'e-type', 'e-amount', 'e-category', 'e-note', 'e-date',
     'e-cadence', 'e-cadence-row', 'edit-cancel', 'preview-actions', 'create',
     'edit-open', 'cancel', 'preview-error', 'composer', 'message', 'send', 'chat-error']
        .forEach(function (id) { el[id] = document.getElementById(id); });

    // --- money and dates: formatting lives here, never on the backend ---

    function fractionDigits(currency) {
        try {
            return new Intl.NumberFormat('en', { style: 'currency', currency: currency })
                .resolvedOptions().maximumFractionDigits;
        } catch (e) {
            return 2;
        }
    }

    function toMajor(minor, currency) {
        return minor / Math.pow(10, fractionDigits(currency));
    }

    function formatMoney(minor, currency) {
        if (minor === null || minor === undefined) { return null; }
        var major = toMajor(minor, currency);
        try {
            return new Intl.NumberFormat(undefined, { style: 'currency', currency: currency }).format(major);
        } catch (e) {
            return major.toFixed(fractionDigits(currency)) + ' ' + (currency || '');
        }
    }

    function toMinor(major, currency) {
        // Scale first, then round, so 17.50 is 1750 and never 1749.
        return Math.round(Number(major) * Math.pow(10, fractionDigits(currency)));
    }

    function formatDate(millis) {
        if (!millis) { return null; }
        return new Date(millis).toLocaleDateString(undefined,
            { year: 'numeric', month: 'short', day: 'numeric' });
    }

    function dateInputValue(millis) {
        var d = new Date(millis || Date.now());
        var pad = function (n) { return String(n).padStart(2, '0'); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    }

    // --- transport ---

    function api(path, options) {
        options = options || {};
        var headers = { 'Content-Type': 'application/json' };
        if (state.token) { headers.Authorization = 'Bearer ' + state.token; }
        return fetch(API + path, {
            method: options.method || 'GET',
            headers: headers,
            body: options.body ? JSON.stringify(options.body) : undefined
        }).then(function (response) {
            // Every response is the ApiResponse envelope: {status, data, message, errorCode}.
            return response.json().catch(function () { return {}; }).then(function (envelope) {
                if (response.ok && envelope && envelope.status === 'success') {
                    return envelope.data;
                }
                if (response.status === 401) { signOut(); }
                throw new Error((envelope && envelope.message)
                    || 'Something went wrong. Please try again.');
            });
        });
    }

    function showError(node, error) {
        node.textContent = error ? error.message : '';
        node.hidden = !error;
    }

    // --- sign in ---

    function signOut() {
        state.token = null;
        sessionStorage.removeItem(TOKEN_KEY);
        el.signin.hidden = false;
        el.conversation.hidden = true;
    }

    function start() {
        el.signin.hidden = true;
        el.conversation.hidden = false;
        el.message.focus();
        api('/categories').then(function (categories) {
            state.categories = categories || [];
        }).catch(function () {
            // Only the edit form's dropdown needs these; chat itself works without them.
            state.categories = [];
        });
    }

    el.signin.addEventListener('submit', function (event) {
        event.preventDefault();
        showError(el['signin-error'], null);
        api('/authenticate', {
            method: 'POST',
            body: { email: el.email.value.trim(), password: el.password.value }
        }).then(function (auth) {
            state.token = auth.accessToken;
            sessionStorage.setItem(TOKEN_KEY, state.token);
            el.password.value = '';
            start();
        }).catch(function (error) { showError(el['signin-error'], error); });
    });

    // --- transcript ---

    var CADENCE_WORDS = {
        DAILY: 'daily', WEEKLY: 'weekly', MONTHLY: 'monthly', YEARLY: 'yearly'
    };

    function appendTurn(role, text) {
        var item = document.createElement('li');
        item.className = 'turn ' + role;
        item.textContent = text;
        el.transcript.appendChild(item);
        item.scrollIntoView({ block: 'nearest' });
        return item;
    }

    /*
     * One entry the backend recorded. The money and the date are formatted here, from
     * the draft's minor units and epoch millis -- the reply sentence never carries
     * either, which is what keeps currency and locale formatting on the client.
     */
    function appendCard(result) {
        var item = appendTurn('assistant', '');
        item.classList.add('card');

        var line = document.createElement('p');
        line.className = 'card-line';
        var draft = result.draft || {};
        var title = draft.payeeName || draft.note || (draft.categoryName || 'Entry');
        var amount = formatMoney(draft.amountMinor, draft.currency);
        line.textContent = amount ? title + ' \u2013 ' + amount : title;
        item.appendChild(line);

        var meta = document.createElement('p');
        meta.className = 'card-meta';
        var parts = [];
        if (draft.categoryName) { parts.push(draft.categoryName); }
        if (draft.cadence) { parts.push('repeats ' + (CADENCE_WORDS[draft.cadence] || '')); }
        else if (draft.txnDate) { parts.push(formatDate(draft.txnDate)); }
        meta.textContent = parts.join(' \u00b7 ');
        item.appendChild(meta);

        var undo = document.createElement('button');
        undo.type = 'button';
        undo.className = 'undo';
        undo.textContent = 'Undo';
        undo.addEventListener('click', function () {
            undo.disabled = true;
            api('/chat/undo', { method: 'POST', body: { messageId: result.messageId } })
                .then(function () {
                    item.classList.add('undone');
                    undo.remove();
                    meta.textContent = 'Removed.';
                })
                .catch(function (error) {
                    undo.disabled = false;
                    showError(el['chat-error'], error);
                });
        });
        item.appendChild(undo);
        item.scrollIntoView({ block: 'nearest' });
    }

    /* Every result is a turn; the ones that recorded something get a card. */
    function renderResults(results) {
        (results || []).forEach(function (result) {
            if (result.transactionId || result.recurringId) {
                appendCard(result);
            } else {
                appendTurn('assistant', result.reply);
            }
        });
    }

    // --- answered question ---

    function renderInsight(insight) {
        el['insight-months'].textContent = '';
        if (!insight || !insight.months || !insight.months.length) {
            el.insight.hidden = true;
            return;
        }
        insight.months.forEach(function (month) {
            var block = document.createElement('div');
            block.className = 'insight-month';

            var heading = document.createElement('h3');
            heading.textContent = month.month;
            block.appendChild(heading);

            var totals = document.createElement('p');
            totals.textContent = 'In ' + formatMoney(month.income, insight.currency)
                + ' · Out ' + formatMoney(month.expenses, insight.currency)
                + ' · Left ' + formatMoney(month.position, insight.currency);
            block.appendChild(totals);

            if (month.categories.length) {
                var rows = document.createElement('dl');
                rows.className = 'insight-rows';
                month.categories.forEach(function (category) {
                    var name = document.createElement('dt');
                    name.textContent = category.name;
                    var amount = document.createElement('dd');
                    amount.textContent = formatMoney(category.amount, insight.currency);
                    rows.appendChild(name);
                    rows.appendChild(amount);
                });
                block.appendChild(rows);
            }
            el['insight-months'].appendChild(block);
        });
        el.insight.hidden = false;
    }

    // --- preview ---

    function text(node, value) {
        node.textContent = value === null || value === undefined ? 'Not set yet' : value;
        node.classList.toggle('pending', value === null || value === undefined);
    }

    function isCapture(draft) {
        return draft && (draft.intent === 'CREATE_TRANSACTION' || draft.intent === 'CREATE_RECURRING');
    }

    /*
     * Only ever shown for a draft the backend did NOT write -- a reading it doubted,
     * or one still short of something. An entry that was recorded has a card instead.
     */
    function renderPreview() {
        var draft = state.draft;
        if (!isCapture(draft) || state.status === 'CREATED' || state.status === 'CONFIRMED') {
            el.preview.hidden = true;
            return;
        }
        var recurring = draft.intent === 'CREATE_RECURRING';
        el.preview.hidden = false;
        text(el['p-type'], draft.txnType === 'INCOME' ? 'Income'
            : draft.txnType === 'EXPENSE' ? 'Expense' : null);
        text(el['p-amount'], formatMoney(draft.amountMinor, draft.currency));
        el['p-amount'].classList.add('amount');
        text(el['p-category'], draft.categoryName);
        text(el['p-note'], draft.note);
        text(el['p-date'], formatDate(draft.txnDate));

        el['p-cadence-label'].hidden = !recurring;
        el['p-cadence'].hidden = !recurring;
        if (recurring) {
            text(el['p-cadence'], draft.cadence ? CADENCE_WORDS[draft.cadence] : null);
        }

        var ready = state.status === 'PARSED';
        el.create.disabled = !ready;
        el.create.textContent = recurring ? 'Create repeating entry'
            : draft.txnType === 'INCOME' ? 'Create Income' : 'Create Expense';
        el.create.title = ready ? '' : 'Answer the question above first';
    }

    function fillEditForm() {
        var draft = state.draft;
        el['e-type'].value = draft.txnType || 'EXPENSE';
        el['e-amount'].value = draft.amountMinor === null || draft.amountMinor === undefined
            ? '' : toMajor(draft.amountMinor, draft.currency);
        el['e-amount'].step = Math.pow(10, -fractionDigits(draft.currency)).toFixed(
            fractionDigits(draft.currency)) || '1';
        el['e-note'].value = draft.note || '';
        el['e-date'].value = dateInputValue(draft.txnDate);

        var recurring = draft.intent === 'CREATE_RECURRING';
        el['e-cadence-row'].hidden = !recurring;
        el['e-cadence'].value = draft.cadence || '';

        el['e-category'].textContent = '';
        var kind = el['e-type'].value === 'INCOME' ? 'INCOME' : 'EXPENSE';
        state.categories.filter(function (c) { return c.kind === kind; }).forEach(function (c) {
            var option = document.createElement('option');
            option.value = c.id;
            option.textContent = c.name;
            option.selected = c.id === draft.categoryId;
            el['e-category'].appendChild(option);
        });
    }

    el['e-type'].addEventListener('change', function () {
        // The category list is kind-specific, so flipping the direction re-offers it.
        var current = el['e-category'].value;
        fillEditForm();
        el['e-category'].value = current;
    });

    el['edit-open'].addEventListener('click', function () {
        fillEditForm();
        el.edit.hidden = false;
        el['preview-actions'].hidden = true;
    });

    el['edit-cancel'].addEventListener('click', function () {
        el.edit.hidden = true;
        el['preview-actions'].hidden = false;
    });

    el.edit.addEventListener('submit', function (event) {
        event.preventDefault();
        var body = {
            messageId: state.messageId,
            txnType: el['e-type'].value,
            categoryId: el['e-category'].value || null,
            note: el['e-note'].value,
            txnDate: el['e-date'].value ? new Date(el['e-date'].value + 'T00:00:00').getTime() : null
        };
        if (el['e-amount'].value !== '') {
            body.amountMinor = toMinor(el['e-amount'].value, state.draft.currency);
        }
        if (!el['e-cadence-row'].hidden && el['e-cadence'].value) {
            body.cadence = el['e-cadence'].value;
        }
        el.edit.hidden = true;
        el['preview-actions'].hidden = false;
        send('/chat/draft', body, el['preview-error']);
    });

    el.create.addEventListener('click', function () {
        showError(el['preview-error'], null);
        el.create.disabled = true;
        api('/chat/confirm', { method: 'POST', body: { messageId: state.messageId } })
            .then(function (reply) {
                // The same card shape as a message that was written without asking, so
                // an entry looks the same however it got there -- Undo included.
                renderResults(reply.results);
                clearDraft();
            })
            .catch(function (error) {
                showError(el['preview-error'], error);
                el.create.disabled = false;
            });
    });

    el.cancel.addEventListener('click', function () {
        showError(el['preview-error'], null);
        api('/chat/reject', { method: 'POST', body: { messageId: state.messageId } })
            .then(function () {
                appendTurn('assistant', 'Discarded. Nothing was added to your ledger.');
                clearDraft();
            })
            .catch(function (error) { showError(el['preview-error'], error); });
    });

    function clearDraft() {
        state.messageId = null;
        state.draft = null;
        state.status = null;
        el.preview.hidden = true;
        el.edit.hidden = true;
        el['preview-actions'].hidden = false;
        el.message.placeholder = PLACEHOLDER;
        el.message.focus();
    }

    // --- one round trip, however it was triggered ---

    function send(path, body, errorNode) {
        showError(errorNode, null);
        el.send.disabled = true;
        return api(path, { method: 'POST', body: body })
            .then(function (reply) {
                state.sessionId = reply.sessionId || state.sessionId;
                state.messageId = reply.messageId;
                state.draft = reply.draft;
                state.status = reply.status;
                if (reply.results && reply.results.length) {
                    renderResults(reply.results);
                } else {
                    // An answered question carries no results, only prose.
                    appendTurn('assistant', reply.reply);
                }
                renderPreview();
                // An answer and a draft are alternatives, never both — the backend
                // sends exactly one of them, so rendering both would show stale data.
                renderInsight(reply.insight);
            })
            .catch(function (error) { showError(errorNode, error); })
            .finally(function () {
                el.send.disabled = false;
                el.message.focus();
            });
    }

    el.composer.addEventListener('submit', function (event) {
        event.preventDefault();
        var message = el.message.value.trim();
        if (!message) { return; }
        appendTurn('user', message);
        el.message.value = '';
        el.message.placeholder = PLACEHOLDER;
        send('/chat', { message: message, sessionId: state.sessionId }, el['chat-error']);
    });

    if (state.token) { start(); } else { el.signin.hidden = false; }
}());

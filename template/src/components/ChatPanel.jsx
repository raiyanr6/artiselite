import React, { useEffect, useRef, useState } from 'react';
import { agentAPI } from '../services/api';
import { Bot, Check, ClipboardCheck, Send, X } from 'lucide-react';

// Matches the agent service limit (max_length=1000 on ChatRequest.message)
const MAX_MESSAGE_LENGTH = 1000;

const SUGGESTED_QUESTIONS = [
  'Which items are low on stock?',
  'How many units went out in the last 7 days?',
  'Give me a dashboard summary.',
  'I want to record stock we just received.',
];

const ACTION_TITLES = {
  create_product: 'Add a new product',
  create_inbound: 'Record inbound (stock received)',
  create_outbound: 'Record outbound (stock dispatched)',
};

// Display order and labels for the approval card; unknown fields are still shown, after these
const FIELD_LABELS = {
  sku: 'SKU',
  productName: 'Product',
  name: 'Name',
  category: 'Category',
  currentStock: 'Current stock',
  quantity: 'Quantity',
  unitCost: 'Unit cost',
  unitPrice: 'Unit price',
  averageCost: 'Average cost',
  lowStockThreshold: 'Low-stock threshold',
  invoiceReference: 'Invoice ref',
  batchNumber: 'Batch',
  customerName: 'Customer',
  salesOrderReference: 'Sales order ref',
  description: 'Description',
  notes: 'Notes',
};

let nextMessageId = 0;
const newMessage = (role, text) => ({ id: ++nextMessageId, role, text });

// Friendly text for every failure; raw error objects never reach the screen
const errorMessage = (error) => {
  const status = error.response?.status;
  const detail = error.response?.data?.detail;
  if (status === 401 || status === 403) {
    return 'Your session has expired. Please log in again.';
  }
  if (status === 404) {
    return 'That conversation has expired. Please ask again.';
  }
  if ((status === 409 || status === 503) && typeof detail === 'string' && detail) {
    return detail;
  }
  if (status === 400 || status === 422) {
    return "That message couldn't be sent. Please shorten or rephrase it.";
  }
  return 'The assistant is unavailable right now. Please try again in a moment.';
};

// The card shows the request exactly as the agent service will send it (plain text, never HTML)
const ActionCard = ({ action, onDecide, disabled }) => {
  const summary = action.summary || {};
  const known = Object.keys(FIELD_LABELS).filter((k) => k in summary);
  const rest = Object.keys(summary).filter((k) => !(k in FIELD_LABELS));
  const rows = [...known, ...rest].filter((k) => summary[k] !== null && summary[k] !== '');

  const sign = { create_inbound: 1, create_outbound: -1 }[action.tool];
  const stockAfter =
    sign && typeof summary.currentStock === 'number' && typeof summary.quantity === 'number'
      ? summary.currentStock + sign * summary.quantity
      : null;

  return (
    <div role="group" aria-label="Change waiting for your approval" className="mr-auto w-full max-w-[95%] rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm">
      <div className="flex items-center font-semibold text-amber-900 mb-2">
        <ClipboardCheck className="w-4 h-4 mr-2" />
        {ACTION_TITLES[action.tool] || 'Approve this change?'}
      </div>
      <dl className="grid grid-cols-[auto,1fr] gap-x-3 gap-y-1 text-gray-800">
        {rows.map((key) => (
          <React.Fragment key={key}>
            <dt className="text-gray-500">{FIELD_LABELS[key] || key}</dt>
            <dd className="break-words">{String(summary[key])}</dd>
          </React.Fragment>
        ))}
        {stockAfter !== null && (
          <>
            <dt className="text-gray-500">Stock after</dt>
            <dd className="font-medium">{stockAfter}</dd>
          </>
        )}
      </dl>
      <p className="mt-2 text-xs text-amber-900">Nothing is saved until you approve.</p>
      {/* Not auto-focused: an Enter meant for the message box must never approve a change */}
      <div className="mt-3 flex space-x-2">
        <button
          onClick={() => onDecide(true)}
          disabled={disabled}
          className="btn-primary flex items-center px-3 py-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <Check className="w-4 h-4 mr-1" /> Approve
        </button>
        <button
          onClick={() => onDecide(false)}
          disabled={disabled}
          className="flex items-center px-3 py-1.5 rounded-lg border border-gray-300 bg-white text-gray-700 hover:bg-gray-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <X className="w-4 h-4 mr-1" /> Reject
        </button>
      </div>
    </div>
  );
};

const ChatPanel = ({ chat, setChat, onClose }) => {
  const [draft, setDraft] = useState('');
  const inFlight = useRef(false);
  const listRef = useRef(null);
  const inputRef = useRef(null);
  const { messages, sending, pendingAction } = chat;
  const inputLocked = sending || Boolean(pendingAction);

  // Escape closes the panel (a waiting change stays waiting)
  useEffect(() => {
    const onKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  // Keep the newest message (or the approval card) in view
  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [messages.length, sending, pendingAction]);

  // Ready to type when the panel opens and after each reply
  useEffect(() => {
    if (!inputLocked) inputRef.current?.focus();
  }, [inputLocked]);

  // Both endpoints answer with either a reply or a change to approve
  const applyResponse = (data) =>
    setChat((c) => ({
      ...c,
      conversationId: data.threadId ?? c.conversationId,
      pendingAction: data.pendingAction ?? null,
      messages: data.pendingAction
        ? c.messages
        : [...c.messages, newMessage('assistant', String(data.reply ?? ''))],
    }));

  const applyError = (error) =>
    setChat((c) => ({
      ...c,
      // 404: the agent service forgot this conversation (e.g. restart), so start a new one next time.
      // 404/409 on a decision: there is nothing waiting any more, so drop the card.
      conversationId: error.response?.status === 404 ? null : c.conversationId,
      pendingAction: [404, 409].includes(error.response?.status) ? null : c.pendingAction,
      messages: [...c.messages, newMessage('error', errorMessage(error))],
    }));

  // The ref blocks a second click/submit before the "sending" state has re-rendered
  const run = async (request, before) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setChat((c) => ({ ...c, sending: true, ...before(c) }));
    try {
      const { data } = await request();
      applyResponse(data);
    } catch (error) {
      applyError(error);
    } finally {
      inFlight.current = false;
      setChat((c) => ({ ...c, sending: false }));
    }
  };

  const send = (raw) => {
    const text = raw.trim();
    if (!text || text.length > MAX_MESSAGE_LENGTH || inputLocked) return;
    setDraft('');
    run(
      () => agentAPI.ask(text, chat.conversationId ?? undefined),
      (c) => ({ messages: [...c.messages, newMessage('user', text)] })
    );
  };

  const decide = (approved) => {
    if (!pendingAction || sending) return;
    const title = ACTION_TITLES[pendingAction.tool] || 'change';
    run(
      () => agentAPI.resume(chat.conversationId, approved),
      (c) => ({ messages: [...c.messages, newMessage('note', `${approved ? 'Approved' : 'Rejected'}: ${title}`)] })
    );
  };

  const handleKeyDown = (e) => {
    // Enter sends, Shift+Enter adds a newline (and don't send mid IME composition)
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send(draft);
    }
  };

  const bubbleClass = {
    user: 'ml-auto bg-primary-600 text-white',
    assistant: 'mr-auto bg-gray-100 text-gray-800',
    error: 'mr-auto bg-red-50 text-red-700 border border-red-200',
    note: 'mx-auto bg-transparent text-gray-500 text-xs italic',
  };

  return (
    <aside
      id="chat-panel"
      role="dialog"
      aria-labelledby="chat-panel-title"
      className="fixed inset-0 sm:inset-y-0 sm:left-auto sm:right-0 sm:w-[400px] z-50 bg-white shadow-xl flex flex-col"
    >
      {/* Header */}
      <div className="flex items-center justify-between h-16 px-4 border-b">
        <div className="flex items-center">
          <Bot className="w-6 h-6 text-primary-600 mr-2" />
          <div>
            <h2 id="chat-panel-title" className="font-semibold text-gray-800">
              Assistant
            </h2>
            <p className="text-xs text-gray-500">Looks up live data; changes need your approval</p>
          </div>
        </div>
        <button
          onClick={onClose}
          aria-label="Close assistant"
          className="text-gray-500 hover:text-gray-700"
        >
          <X className="w-6 h-6" />
        </button>
      </div>

      {/* Messages */}
      <div ref={listRef} role="log" aria-label="Conversation" className="flex-1 overflow-y-auto p-4 space-y-3">
        {messages.length === 0 && (
          <div>
            <p className="text-sm text-gray-600 mb-3">
              Ask about stock, or ask me to add a product or record stock in/out. Try one of these:
            </p>
            <div className="flex flex-wrap gap-2">
              {SUGGESTED_QUESTIONS.map((q) => (
                <button
                  key={q}
                  onClick={() => send(q)}
                  disabled={inputLocked}
                  className="text-sm text-left px-3 py-2 rounded-full border border-primary-200 bg-primary-50 text-primary-700 hover:bg-primary-100 transition-colors disabled:opacity-50"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Plain text only: model output is untrusted, so it is never rendered as HTML */}
        {messages.map((m) => (
          <div
            key={m.id}
            className={`max-w-[85%] w-fit px-3 py-2 rounded-lg text-sm whitespace-pre-wrap break-words ${bubbleClass[m.role]}`}
          >
            {m.text}
          </div>
        ))}

        {pendingAction && <ActionCard action={pendingAction} onDecide={decide} disabled={sending} />}

        {sending && (
          <div className="mr-auto w-fit px-3 py-3 rounded-lg bg-gray-100 flex items-center space-x-1">
            <span className="sr-only">Assistant is thinking</span>
            <span className="w-2 h-2 rounded-full bg-gray-400 animate-bounce" />
            <span className="w-2 h-2 rounded-full bg-gray-400 animate-bounce [animation-delay:150ms]" />
            <span className="w-2 h-2 rounded-full bg-gray-400 animate-bounce [animation-delay:300ms]" />
          </div>
        )}
      </div>

      {/* Input */}
      <form
        className="border-t p-3"
        onSubmit={(e) => {
          e.preventDefault();
          send(draft);
        }}
      >
        <div className="flex items-end space-x-2">
          <label htmlFor="chat-input" className="sr-only">
            Message
          </label>
          <textarea
            id="chat-input"
            ref={inputRef}
            rows={2}
            value={draft}
            maxLength={MAX_MESSAGE_LENGTH}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={inputLocked}
            placeholder={pendingAction ? 'Approve or reject the change above first' : 'Ask a question…'}
            className="input-field resize-none disabled:bg-gray-100"
          />
          <button
            type="submit"
            disabled={inputLocked || !draft.trim()}
            aria-label="Send message"
            className="btn-primary px-3 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Send className="w-5 h-5" />
          </button>
        </div>
        <div className="flex justify-between mt-1 text-xs text-gray-500">
          <span>Enter to send, Shift+Enter for a new line</span>
          <span>{draft.length > MAX_MESSAGE_LENGTH - 100 && `${draft.length}/${MAX_MESSAGE_LENGTH}`}</span>
        </div>
      </form>
    </aside>
  );
};

export default ChatPanel;

/**
 * Help: short, user-facing guide to the app — what each page is for and how to do the common things. Open to everyone;
 * the text follows the signed-in user's role (what they may do), and the administrators' pages are described only to
 * administrators. Static content: no API calls.
 */
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Bell, BookOpen, CircleX, Radio, Split, Users, Workflow, Zap, type LucideIcon } from 'lucide-react';
import { PageIntro, PageWrap } from '../../components/ui';
import { usePermissions, type Permissions } from '../../auth/permissions';

interface Topic {
  path: string;
  title: string;
  icon: LucideIcon;
  /** One sentence: what the page is for. */
  what: string;
  /** How to do things there; already filtered by role. */
  how: ReactNode[];
}

function topics(p: Permissions): Topic[] {
  return [
    {
      path: '/alerts', title: 'Alerts', icon: Bell,
      what: p.restricted
        ? 'The live list of your team’s alerts. New alerts and updates appear by themselves — no need to refresh.'
        : 'The live list of every team’s alerts. New alerts and updates appear by themselves — no need to refresh.',
      how: [
        'Filter by status (Active, Open, Acked, Resolved) and severity, search by text, and sort by severity, last seen or number of events.',
        'Click an alert to open its card: the event that caused it, the attached runbook, who was notified and how, and its history.',
        'Repeated events do not create new alerts: the “Events” column counts them.',
        p.operate
          ? <><b>Acknowledge</b> (Ack) says “I’m on it” and keeps the alert open. <b>Resolve</b> closes it once the problem is fixed{p.admin ? '; you can attach a fix guide that later alerts of the same rule will link to' : ''}.</>
          : 'You can look at alerts; acknowledging and resolving them is for operators and administrators.',
        'Some alerts resolve themselves when a recovery event arrives — the card’s history says so.',
        'The “Live” dot in the header pauses and resumes live updates.',
      ],
    },
    {
      path: '/rules', title: 'Rules', icon: Workflow,
      what: 'Rules decide which events become alerts for a team, how severe they are, and how people are notified.',
      how: [
        'Each rule has a condition (“when should this rule fire?”), a severity, the channels to notify (Email, SMS, Call, UI) and how repeats are grouped into one alert.',
        'A rule can resolve its alerts automatically when a recovery event matches, and can always attach a specific runbook.',
        p.admin
          ? <>Create one with <b>New rule</b> — the editor shows live how many recent events it would match. Switch a rule off with its toggle, duplicate or delete it from the list.</>
          : 'Click a rule to see all its settings. Rules are changed by administrators.',
      ],
    },
    {
      path: '/runbooks', title: 'Runbooks', icon: BookOpen,
      what: 'Runbooks are instruction files (any format: PDF, Markdown, text, …) linked to alerts so whoever handles the alert knows what to do.',
      how: [
        'A runbook is attached when its condition matches the alert’s event, or always, when a rule picks it. Your team’s runbooks come before global ones.',
        'Open alerts follow changes: add, edit or delete a runbook and they switch to the runbook that fits now.',
        p.admin
          ? <>Add one with <b>New runbook</b> or by dropping a file on the page; click a row to edit its file, scope (a team or global) and condition.</>
          : p.manageRunbooks
            ? <>Add your team’s runbooks with <b>New runbook</b> or by dropping a file on the page; click a row to edit or replace it. Global runbooks are managed by administrators.</>
            : 'Click a runbook to see when it is attached, or Open to read it. Runbooks are managed by operators and administrators.',
      ],
    },
  ];
}

const ADMIN_TOPICS: Topic[] = [
  {
    path: '/unrouted', title: 'Unrouted', icon: Split,
    what: 'Events that matched no team. They still become alerts (severity Unknown), so nothing is lost.',
    how: [
      'Expand one to see a sample event and the teams it probably belongs to.',
      <>Use <b>Rule</b> next to a suggested team to create a rule for it, or change the team’s condition in Teams &amp; Users.</>,
    ],
  },
  {
    path: '/recipients', title: 'Teams & Users', icon: Users,
    what: 'Who receives alerts. A team’s condition says which events belong to it; its users get the notifications.',
    how: [
      'Team: name and condition (the editor shows how many recent events would match). Strict mode compares fields only by their exact path and type.',
      'User: contacts, time zone, working hours and preferred channels in order. A user gets one notification per alert, on the first channel allowed right now — outside working hours only channels set to “Always” or to custom hours that cover that time.',
      'Give a user a role and a password to let them sign in: Viewer (looks), Operator (also handles alerts and their team’s runbooks) or Admin (everything).',
    ],
  },
  {
    path: '/sources', title: 'Sources', icon: Radio,
    what: 'Where events come from: an HTTP webhook, a Kafka topic or a log file. Events may have any JSON shape.',
    how: [
      <>Add a source with <b>Add source</b>; an HTTP source gets an API key (shown once — copy it, or <b>Rotate</b> later).</>,
      <><b>Send test events</b> pushes sample events through the real pipeline.</>,
    ],
  },
  {
    path: '/simulator', title: 'Simulator', icon: Zap,
    what: 'Email, SMS and phone calls are simulated: here you see every delivery attempt live.',
    how: [
      'Switch a channel off to simulate an outage — messages wait and are retried, then delivered when it is back on.',
      'Emails can also be read in MailHog (link at the bottom of the menu).',
    ],
  },
  {
    path: '/failed', title: 'Failed Events', icon: CircleX,
    what: 'Events whose processing failed even after retries. They are kept so nothing is lost.',
    how: [<><b>Replay</b> sends an event through processing again (or <b>Replay all</b>) — for example after fixing a rule.</>],
  },
];

const ROLE_SUMMARY = (p: Permissions) => p.admin
  ? 'You are an administrator: you see every team and can change everything, including the pages at the bottom.'
  : p.operate
    ? 'You are an operator: you see your team’s alerts, rules and runbooks, handle alerts (acknowledge, resolve) and manage your team’s runbooks.'
    : 'You are a viewer: you see your team’s alerts, rules and runbooks, read-only.';

function TopicCard({ t }: { t: Topic }) {
  const Ic = t.icon;
  return (
    <section className="card p-5">
      <Link to={t.path} className="inline-flex items-center gap-2 text-[15px] font-semibold text-gray-900 hover:underline">
        <Ic className="w-[18px] h-[18px] text-gray-500" strokeWidth={1.75} />{t.title}
      </Link>
      <p className="text-sm text-gray-600 mt-1.5">{t.what}</p>
      <ul className="mt-3 space-y-1.5 list-disc pl-5 text-[13px] text-gray-600 leading-5">
        {t.how.map((h, i) => <li key={i}>{h}</li>)}
      </ul>
    </section>
  );
}

export function HelpPage() {
  const p = usePermissions();
  return (
    <PageWrap>
      <PageIntro>
        This app turns events from your systems into alerts for the right team, attaches instructions (runbooks), and notifies
        people by email, SMS, phone call or here in the app. {ROLE_SUMMARY(p)}
      </PageIntro>

      <div className="space-y-4">
        {topics(p).map(t => <TopicCard key={t.path} t={t} />)}
      </div>

      {p.admin && (
        <>
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-gray-500 mt-8 mb-3">Administrator pages</h2>
          <div className="space-y-4">
            {ADMIN_TOPICS.map(t => <TopicCard key={t.path} t={t} />)}
          </div>
        </>
      )}

      <p className="text-[13px] text-gray-500 mt-8">
        Conditions everywhere work the same way: pick a field of the event, a comparison (equals, greater than, contains, in a list,
        matches a pattern, exists…) and a value; combine several with “all of” (AND) or “any of” (OR). Fields are found by name even
        when events from different systems are shaped differently.
      </p>
    </PageWrap>
  );
}

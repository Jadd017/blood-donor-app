
# 🩸 BloodDonor: Blood Donor Matching for Lebanon



![Status](https://img.shields.io/badge/status-work%20in%20progress-orange)




![Platform](https://img.shields.io/badge/platform-Android-3DDC84)




![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)




![Backend](https://img.shields.io/badge/backend-Ktor-087CFA)




![Database](https://img.shields.io/badge/database-PostgreSQL%20%2B%20PostGIS-336791)



> An Android app that connects people in Lebanon who urgently need blood with **compatible, eligible donors nearby**, and notifies only the people who can actually help.

---

## The problem

In Lebanon, urgent blood requests are usually shared through WhatsApp groups and social media. They reach thousands of people who can't help (wrong blood type, too far away, or donated recently), while the right donors often miss them. Phone numbers end up posted publicly, and old requests keep circulating long after the need is met.

## The solution

Anyone can post an urgent request from the hospital where the patient is. The server then finds donors who are:

- **Compatible** with the patient for the component needed (whole blood, red cells, platelets or plasma)
- **Eligible** to donate again, based on their last donation date
- **Nearby**, based on the distance between the donor's area and the hospital

Only those donors get a push notification. If not enough people respond, the search radius widens automatically.

---

## Features

### ✅ Built
- **Sign-in:** email/password with required email verification and password reset, plus Google Sign-In (Credential Manager)
- **Donor profiles:** name, Lebanese mobile number (normalized to +961, one account per number), blood type, area, last donation date, notification opt-in
- **Hospitals:** Lebanese hospitals imported from OpenStreetMap, cleaned of clinics, dispensaries and duplicates
- **Blood requests:** patient blood type, component, units (1–10), deadline (6–72 h) and an optional note, with "mark fulfilled", "cancel" and automatic expiry
- **Compatibility rules** for each component (red cells/whole blood, plasma, platelets), unit-tested for every blood-type combination

### 🧪 In testing
- **Smart notifications** sent from the backend through Firebase Cloud Messaging, in waves:
  `15 km → 30 km (after 30 min) → 60 km (after 1 h) → all of Lebanon (after 2 h)`
- Up to 50 closest donors per wave, each donor notified only once per request, and at most 3 alerts per donor per day
- Donation-gap eligibility (configurable)

### 🛠️ Planned
- **"I can help":** a donor commits to help, and only then do donor and requester see each other's contact details
- **Anti-spam:** daily request limits, a waiting period for new accounts, reporting with automatic hiding, and blocking
- In-app account deletion
- Deployment on a Google Cloud e2-micro VM, and release on Google Play
- Arabic language, donation history, "eligible again" reminders and thank-you badges

---

## Architecture

```mermaid
flowchart LR
    A["Android app